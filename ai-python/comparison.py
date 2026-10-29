"""Comparação isolada de uma cópia: não altera o catálogo usado nas buscas."""
from collections import OrderedDict

import numpy as np

from .abbreviations import prepare_dictionary


# Só absorve erro numérico de cosseno. Não representa um limiar de similaridade
# aproximada (99,99%, por exemplo, continua exigindo uma decisão humana).
SEMANTIC_EXACT_TOLERANCE = 1e-7


class ScopedComparison:
    def __init__(self, embedder, max_cached_texts=100_000):
        self.embedder = embedder
        self.cache = OrderedDict()
        self.max_cached_texts = max_cached_texts

    def _remember(self, text, vector):
        if not text:
            return
        self.cache[text] = np.asarray(vector, dtype=np.float32).copy()
        self.cache.move_to_end(text)
        while len(self.cache) > self.max_cached_texts:
            self.cache.popitem(last=False)

    def seed(self, texts, vectors):
        for text, vector in zip(texts, vectors):
            if text not in self.cache:
                self._remember(text, vector)

    def compare(self, item, candidates, entries, version, activity_weight, qualifier_weight):
        dictionary, _, _ = prepare_dictionary(entries, version)
        # ID é usado apenas para excluir o próprio registro, nunca como critério
        # de equivalência. Itens de lote ainda podem não ter catalog_id.
        current_id = item.get('catalog_id')
        candidates = [dict(row) for row in candidates
                      if not current_id or row.get('catalog_id') != current_id]
        if len({row.get('catalog_id') for row in candidates if row.get('catalog_id')}) != len(
                [row for row in candidates if row.get('catalog_id')]):
            raise ValueError('A lista de comparação contém identificadores repetidos.')

        def parts(row):
            split = dictionary.split(row['descricao_item'])
            return split, ('' if split.needs_review else split.activity), ' | '.join(split.qualifier_texts)

        _, query_activity, query_qualifiers = parts(item)
        parsed = [parts(row) for row in candidates]
        activity_weight = activity_weight if query_activity else 0.0
        qualifier_weight = qualifier_weight if query_qualifiers else 0.0
        total = activity_weight + qualifier_weight
        if total:
            activity_weight /= total
            qualifier_weight /= total
        weights = {'activity': activity_weight, 'qualifiers': qualifier_weight}
        if not candidates:
            return {'results': [], 'model': self.embedder.name, 'weights': weights}

        texts = list(dict.fromkeys([query_activity, query_qualifiers]
                                  + [text for _, activity, qualifiers in parsed for text in (activity, qualifiers)]))
        # Referências desta requisição sobrevivem a eventuais expulsões do LRU.
        local = {text: self.cache[text] for text in texts if text in self.cache}
        for text in local:
            self.cache.move_to_end(text)
        missing = [text for text in texts if text and text not in self.cache]
        # Encoder processa os textos novos em lotes; os textos já verificados
        # ficam em cache para não gerar seus embeddings a cada pendente.
        if missing:
            vectors = np.asarray(self.embedder.embed(missing), dtype=np.float32)
            if vectors.shape != (len(missing), self.embedder.dimension) or not np.isfinite(vectors).all():
                raise ValueError('O modelo retornou vetores inválidos.')
            for text, vector in zip(missing, vectors):
                self._remember(text, vector)
                local[text] = vector
        zero = np.zeros(self.embedder.dimension, dtype=np.float64)

        def unit(text):
            if not text:
                return zero
            vector = local[text].astype(np.float64)
            norm = np.linalg.norm(vector)
            return vector / norm if norm > 1e-12 else zero

        query_activity_vector = unit(query_activity)
        query_qualifier_vector = unit(query_qualifiers)
        results = []
        for row, (split, activity, qualifiers) in zip(candidates, parsed):
            score = float(np.clip(activity_weight * np.dot(unit(activity), query_activity_vector)
                                  + qualifier_weight * np.dot(unit(qualifiers), query_qualifier_vector), -1, 1))
            results.append(dict(row, similaridade_semantica=score,
                                semantic_exact=bool(score >= 1.0 - SEMANTIC_EXACT_TOLERANCE),
                                atividade=split.activity, qualificadores=list(split.qualifier_texts)))
        results.sort(key=lambda row: (row['semantic_exact'], row['similaridade_semantica']), reverse=True)
        return {'results': results[:20], 'model': self.embedder.name, 'weights': weights}
