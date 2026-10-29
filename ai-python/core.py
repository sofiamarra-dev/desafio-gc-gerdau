import json
import os
import tempfile
import threading
import uuid
from functools import lru_cache, wraps
from pathlib import Path

import numpy as np

from .embedder import normalized, unit_vector
from .activity_ranking import ActivityRanking, terms
from .abbreviations import INITIAL_DICTIONARY, prepare_dictionary, SPLITTER_VERSION


FIELDS = ['grupo_servico', 'codigo_fornecedor', 'codigo_servico', 'descricao_item', 'unidade_medida', 'unidade_descricao', 'catalog_id']

# Pesos entre a atividade e os qualificadores extraídos da própria descrição.
DESCRIPTION_WEIGHT = 0.90
CONTEXT_WEIGHT = 0.10
INDEX_FORMAT = 4


@lru_cache(maxsize=50_000)
def split_description(text: str):
    return INITIAL_DICTIONARY.split(text)


def description_text(item: dict) -> str:
    """Atividade, preservando expressões protegidas e termos ainda indefinidos."""
    parts = split_description(item.get('descricao_item', ''))
    return '' if parts.needs_review else parts.activity


def context_text(item: dict) -> str:
    """Qualificadores com percentuais; grupo e unidade não entram neste embedding."""
    return ' | '.join(split_description(item.get('descricao_item', '')).qualifier_texts)


def locked(method):
    @wraps(method)
    def call(self, *args, **kwargs):
        with self.lock:
            return method(self, *args, **kwargs)
    return call


class Catalog:
    def __init__(self, embedder, data_dir: Path,
                 description_weight: float = DESCRIPTION_WEIGHT,
                 context_weight: float = CONTEXT_WEIGHT,
                 activity_text_bonus: float = 0.25):
        weights = np.array([description_weight, context_weight], dtype=float)
        if not np.isfinite(weights).all() or (weights < 0).any() or not np.isclose(weights.sum(), 1.0):
            raise ValueError('Os pesos devem ser finitos, não negativos e somar 1.')
        if not np.isfinite(activity_text_bonus) or not 0 <= activity_text_bonus <= 1:
            raise ValueError('O bônus textual deve estar entre 0 e 1.')
        self.activity_text_bonus = float(activity_text_bonus)
        self.activity_ranking = None
        self.description_weight = float(description_weight)
        self.context_weight = float(context_weight)
        self.embedder = embedder
        self.data_dir = data_dir
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.items: list[dict] = []
        self.description_vectors = np.zeros((0, 0), dtype=np.float32)
        self.context_vectors = np.zeros((0, 0), dtype=np.float32)
        self.requires_reindex = False
        self.revision = None
        self.lock = threading.RLock()
        self.dictionary = INITIAL_DICTIONARY
        self.dictionary_fingerprint = None
        self._split = lru_cache(maxsize=50_000)(self.dictionary.split)
        dictionary_path = self.data_dir / 'dictionary.json'
        if dictionary_path.exists():
            contents = json.loads(dictionary_path.read_text(encoding='utf-8'))
            self.dictionary, _, self.dictionary_fingerprint = prepare_dictionary(contents['entries'], contents['version'])
            self._split = lru_cache(maxsize=50_000)(self.dictionary.split)
        self._load()
        if not self.requires_reindex:
            self._refresh_activity_ranking()

    def _refresh_activity_ranking(self):
        self.activity_ranking = ActivityRanking([self._description_text(item) for item in self.items])

    @locked
    def update_dictionary(self, entries, version):
        dictionary, clean, fingerprint = prepare_dictionary(entries, version)
        if version == self.dictionary.version:
            if fingerprint != self.dictionary_fingerprint:
                raise ValueError('Esta versão já identifica outro conteúdo de dicionário.')
            return
        with tempfile.NamedTemporaryFile(mode='w', dir=self.data_dir, delete=False, encoding='utf-8') as file:
            json.dump({'version': version, 'entries': clean}, file, ensure_ascii=False)
            temporary = Path(file.name)
        try:
            os.replace(temporary, self.data_dir / 'dictionary.json')
        finally:
            temporary.unlink(missing_ok=True)
        self._split.cache_clear()
        self.dictionary = dictionary
        self.dictionary_fingerprint = fingerprint
        self._split = lru_cache(maxsize=50_000)(dictionary.split)
        self.description_vectors = np.zeros((0, 0), dtype=np.float32)
        self.context_vectors = np.zeros((0, 0), dtype=np.float32)
        self.requires_reindex = True
        self.activity_ranking = None

    def _description_text(self, item):
        parts = self._split(item.get('descricao_item', ''))
        return '' if parts.needs_review else parts.activity

    def _context_text(self, item):
        return ' | '.join(self._split(item.get('descricao_item', '')).qualifier_texts)

    def _load(self):
        json_file = self.data_dir / 'catalog.json'
        if json_file.exists():
            try:
                contents = json.loads(json_file.read_text(encoding='utf-8'))
                # Preserva os dados/revisão mesmo quando apenas os vetores ficaram obsoletos.
                self.items = contents['items']
                self.revision = contents.get('revision')
                if contents.get('embedder') != self.embedder.name: # aqui impede de comparar vetores gerados por outro modelo
                    self.requires_reindex = True
                    return

                if (contents.get('index_format') == INDEX_FORMAT
                        and contents.get('splitter_version') == SPLITTER_VERSION
                        and contents.get('dictionary_version') == self.dictionary.version
                        and contents.get('dictionary_fingerprint') == self.dictionary_fingerprint):
                    with np.load(self.data_dir / 'vectors.npz', allow_pickle=False) as stored:
                        descriptions = stored['description']
                        contexts = stored['context']
                        if str(stored['generation'].item()) != contents.get('generation'):
                            self.requires_reindex = True
                            return
                    if descriptions.ndim != 2 or len(contents['items']) != len(descriptions):
                        self.requires_reindex = True
                        return
                    if (contexts.shape != descriptions.shape or descriptions.shape[1] != self.embedder.dimension
                            or not np.isfinite(descriptions).all() or not np.isfinite(contexts).all()):
                        self.requires_reindex = True
                        return
                    self.description_vectors = unit_vector(descriptions)
                    self.context_vectors = unit_vector(contexts)
                    self.items = contents['items']
                    self.revision = contents.get('revision')
                else:
                    self.requires_reindex = True
                    return
            except (OSError, ValueError, KeyError):
                self.requires_reindex = True

    @locked
    def replace(self, incoming: list[dict], revision=None, allow_empty=False) -> int:
        registros = []
        for raw in incoming:

            item = {key: str(raw.get(key, '') or '').strip() for key in FIELDS}

            if item['codigo_servico'] and item['descricao_item']:
                parts = self._split(item['descricao_item'])
                item.update(atividade=parts.activity, qualificadores=list(parts.qualifier_texts),
                            separacao_requer_revisao=parts.needs_review)
                registros.append(item)

        if not registros and not (allow_empty and not incoming):
            raise ValueError('A planilha não contém códigos com descrição preenchida.')

        if len(registros) > 50_000:
            raise ValueError('Limitado a 50.000 linhas. Importe uma amostra menor.')

        with self.lock:
            description_vectors = self._encode_cached(
                [self._description_text(item) for item in registros],
                [self._description_text(item) for item in self.items], self.description_vectors)
            context_vectors = self._encode_cached(
                [self._context_text(item) for item in registros],
                [self._context_text(item) for item in self.items], self.context_vectors)

        for matrix in (description_vectors, context_vectors):
            if matrix.ndim != 2 or len(matrix) != len(registros) or matrix.shape[1] == 0:
                raise ValueError('Quantidade ou formato de vetores incompatível com os serviços.')
            if matrix.shape != description_vectors.shape:
                raise ValueError('Os vetores de descrição e contexto devem ter a mesma dimensão.')

        description_vectors = unit_vector(description_vectors)
        context_vectors = unit_vector(context_vectors)

        with self.lock:
            generation = uuid.uuid4().hex

            with tempfile.NamedTemporaryFile(mode='wb', dir=self.data_dir, delete=False) as f: # cria um arquivo para armazenar os vetores
                np.savez(f, description=description_vectors, context=context_vectors, generation=generation)
                tmp_npy = Path(f.name)

            with tempfile.NamedTemporaryFile(mode='w', dir=self.data_dir, delete=False, encoding='utf-8') as f: # também cria um arquivo, mas para armazenar os serviços
                json.dump({'embedder': self.embedder.name, 'index_format': INDEX_FORMAT,
                           'splitter_version': SPLITTER_VERSION,
                           'dictionary_version': self.dictionary.version,
                           'dictionary_fingerprint': self.dictionary_fingerprint, 'generation': generation,
                           'revision': revision, 'items': registros}, f, ensure_ascii=False)
                tmp_json = Path(f.name)

            # aqui subistitui os arquivos antigos pelos novos com as informações novas
            os.replace(tmp_npy, self.data_dir / 'vectors.npz')
            os.replace(tmp_json, self.data_dir / 'catalog.json')

            self.items = registros
            self.description_vectors = description_vectors
            self.context_vectors = context_vectors
            self.requires_reindex = False
            self.revision = revision
            self._refresh_activity_ranking()

        return len(registros)

    def _encode_cached(self, texts, previous_texts, previous_vectors):
        if not texts:
            return np.zeros((0, self.embedder.dimension), dtype=np.float32)
        cache = dict(zip(previous_texts, previous_vectors))
        cache[''] = np.zeros(self.embedder.dimension, dtype=np.float32)
        missing = list(dict.fromkeys(text for text in texts if text not in cache))
        if missing:
            encoded = self.embedder.embed(missing)
            if encoded.shape != (len(missing), self.embedder.dimension) or not np.isfinite(encoded).all():
                raise ValueError('O modelo retornou vetores inválidos.')
            cache.update(zip(missing, encoded))
        return np.stack([cache[text] for text in texts])

    @locked
    def search(self, query: str, limit: int = 20) -> list[dict]: #metodo que retorna a lista dos mais proximos

        if not query.strip(): # verifica se a busca é valida
            raise ValueError('Informe o código ou a descrição do serviço.')
        with self.lock:

            if self.requires_reindex:
                raise ValueError('O índice precisa ser reconstruído. Pesquise pelo Java para sincronizar o catálogo SQL, ou envie os registros para /sync.')
            if not self.items:
                return []

            items = list(self.items)
            description_vectors = self.description_vectors.copy()
            context_vectors = self.context_vectors.copy()

        query_item = {'descricao_item': query}
        activity, qualifiers = self._description_text(query_item), self._context_text(query_item)
        query_vectors = unit_vector(self._encode_cached([activity, qualifiers], [], []))
        description_scores = description_vectors @ query_vectors[0]
        context_scores = context_vectors @ query_vectors[1]
        # Sem qualificadores na consulta, não premiamos/penalizamos os dos itens.
        # A presença no item não muda os pesos, evitando favorecer itens incompletos.
        activity_weight = self.description_weight if activity else 0.0
        qualifier_weight = self.context_weight if qualifiers else 0.0
        total_weight = activity_weight + qualifier_weight
        if total_weight:
            activity_weight /= total_weight
            qualifier_weight /= total_weight
        scores = activity_weight * description_scores + qualifier_weight * context_scores
        semantic_scores = scores.copy()

        buscaNorm = normalized(query)
        # os conjuntos comparam palavras inteiras, a "maça", que vira "maca", não coincide com 'macaco'.
        query_words = set(buscaNorm.split())

        exact_matches = np.zeros(len(items), dtype=bool)
        word_matches = np.zeros(len(items), dtype=bool)

        for i, item in enumerate(items):
            description_norm = normalized(item['descricao_item'])
            description_words = set(description_norm.split())
            # Em consultas com mais de uma palavra, exige todas na descrição.
            word_matches[i] = bool(query_words) and query_words.issubset(description_words)
            if buscaNorm and buscaNorm in (normalized(item['codigo_servico']), description_norm):
                exact_matches[i] = True
                scores[i] = 1.0
        nItens = min(max(1, limit), 20, len(items))

        # a prioridade é feito com primeiro: código/descrição exatos, depois, palavras inteiras e por fim, nota semântica, com as siglas valendo 10% e o texto livre 90%

        # O 90/10 continua sendo a similaridade semântica. O bônus de palavras
        # apenas organiza resultados, depois das prioridades exata/contém.
        if self.activity_ranking is None:
            self._refresh_activity_ranking()
        lexical_scores = self.activity_ranking.scores(activity)
        ranking_scores = scores + self.activity_text_bonus * lexical_scores
        top = np.lexsort((-ranking_scores, -word_matches.astype(int), -exact_matches.astype(int)))[:nItens]

        return [dict(items[int(i)],
                     pontuacao_ordenacao=round(float(ranking_scores[i]), 4),
                     pontuacao_textual_atividade=round(float(lexical_scores[i]), 4),
                     termos_atividade_encontrados=sorted(set(terms(activity)) & set(terms(self._description_text(items[int(i)])))),
                     similaridade=round(float(np.clip(scores[i], -1, 1)), 4),
                     similaridade_semantica=round(float(np.clip(semantic_scores[i], -1, 1)), 4),
                     similaridade_atividade=round(float(np.clip(description_scores[i], -1, 1)), 4),
                     similaridade_qualificadores=round(float(np.clip(context_scores[i], -1, 1)), 4),
                     pesos_aplicados={'atividade': activity_weight, 'qualificadores': qualifier_weight},
                     similaridade_descricao=round(float(np.clip(description_scores[i], -1, 1)), 4),
                     similaridade_contexto=round(float(np.clip(context_scores[i], -1, 1)), 4),
                     correspondencia_palavras=bool(word_matches[i]),
                     correspondencia_exata=(bool(buscaNorm) and buscaNorm in (
                         normalized(items[int(i)]['codigo_servico']),
                         normalized(items[int(i)]['descricao_item'])))) for i in top]

        # retorna o codigo do serviço, a descrição, similaridade e se é 100%
