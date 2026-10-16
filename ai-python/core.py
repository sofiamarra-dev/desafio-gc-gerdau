import json
import os
import tempfile
import threading
from pathlib import Path

import numpy as np

from .embedder import normalized, unit_vector


FIELDS = ['grupo_servico', 'codigo_fornecedor', 'codigo_servico', 'descricao_item', 'unidade_medida', 'unidade_descricao']

# aqui são constantes para o peso que as palavras tem, o texto principal tem 90% de peso e as siglas e contexto tem 10%
DESCRIPTION_WEIGHT = 0.90
CONTEXT_WEIGHT = 0.10


def description_text(item: dict) -> str:
    """Texto principal: somente a descrição livre do serviço."""
    return item.get('descricao_item', '')


def context_text(item: dict) -> str:
    """Texto de apoio: grupo e unidade, sem os códigos identificadores."""
    group = item.get('grupo_servico', '')
    unit = item.get('unidade_descricao', '') or item.get('unidade_medida', '')
    return f'Grupo: {group}. Unidade: {unit}.'


class Catalog:
    def __init__(self, embedder, data_dir: Path,
                 description_weight: float = DESCRIPTION_WEIGHT,
                 context_weight: float = CONTEXT_WEIGHT):
        weights = np.array([description_weight, context_weight], dtype=float)
        if not np.isfinite(weights).all() or (weights < 0).any() or not np.isclose(weights.sum(), 1.0):
            raise ValueError('Os pesos devem ser finitos, não negativos e somar 1.')
        self.description_weight = float(description_weight)
        self.context_weight = float(context_weight)
        self.embedder = embedder
        self.data_dir = data_dir
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.items: list[dict] = []
        self.description_vectors = np.zeros((0, 0), dtype=np.float32)
        self.context_vectors = np.zeros((0, 0), dtype=np.float32)
        self.requires_reindex = False
        self.lock = threading.RLock()
        self._load()

    def _load(self):
        json_file = self.data_dir / 'catalog.json'
        if json_file.exists():
            try:
                contents = json.loads(json_file.read_text(encoding='utf-8'))
                if contents.get('embedder') != self.embedder.name: # aqui impede de comparar vetores gerados por outro modelo
                    self.requires_reindex = True
                    return

                if contents.get('index_format', 1) in (2, 3):
                    # O formato 2 já possui os vetores separados; ignora o antigo combinado.
                    with np.load(self.data_dir / 'vectors.npz', allow_pickle=False) as stored:
                        descriptions = stored['description']
                        contexts = stored['context']
                    if descriptions.ndim != 2 or len(contents['items']) != len(descriptions):
                        self.requires_reindex = True
                        return
                    if contexts.shape != descriptions.shape or descriptions.shape[1] == 0:
                        self.requires_reindex = True
                        return
                    self.description_vectors = unit_vector(descriptions)
                    self.context_vectors = unit_vector(contexts)
                    self.items = contents['items']
                else:
                    self.requires_reindex = True
                    return
            except (OSError, ValueError, KeyError):
                self.requires_reindex = True

    def replace(self, incoming: list[dict]) -> int:
        registros = []
        for raw in incoming:

            item = {key: str(raw.get(key, '') or '').strip() for key in FIELDS}

            if item['codigo_servico'] and item['descricao_item']:
                registros.append(item)

        if not registros:
            raise ValueError('A planilha não contém códigos com descrição preenchida.')

        if len(registros) > 50_000:
            raise ValueError('Limitado a 50.000 linhas. Importe uma amostra menor.')


        description_vectors = self.embedder.embed([description_text(item) for item in registros])
        context_vectors = self.embedder.embed([context_text(item) for item in registros])

        for matrix in (description_vectors, context_vectors):
            if matrix.ndim != 2 or len(matrix) != len(registros) or matrix.shape[1] == 0:
                raise ValueError('Quantidade ou formato de vetores incompatível com os serviços.')
            if matrix.shape != description_vectors.shape:
                raise ValueError('Os vetores de descrição e contexto devem ter a mesma dimensão.')

        description_vectors = unit_vector(description_vectors)
        context_vectors = unit_vector(context_vectors)

        with self.lock:

            with tempfile.NamedTemporaryFile(mode='wb', dir=self.data_dir, delete=False) as f: # cria um arquivo para armazenar os vetores
                np.savez(f, description=description_vectors, context=context_vectors)
                tmp_npy = Path(f.name)

            with tempfile.NamedTemporaryFile(mode='w', dir=self.data_dir, delete=False, encoding='utf-8') as f: # também cria um arquivo, mas para armazenar os serviços
                json.dump({'embedder': self.embedder.name, 'index_format': 3, 'items': registros}, f, ensure_ascii=False)
                tmp_json = Path(f.name)

            # aqui subistitui os arquivos antigos pelos novos com as informações novas
            os.replace(tmp_npy, self.data_dir / 'vectors.npz')
            os.replace(tmp_json, self.data_dir / 'catalog.json')

            self.items = registros
            self.description_vectors = description_vectors
            self.context_vectors = context_vectors
            self.requires_reindex = False

        return len(registros)

    def search(self, query: str, limit: int = 20) -> list[dict]: #metodo que retorna a lista dos mais proximos

        if not query.strip(): # verifica se a busca é valida
            raise ValueError('Informe o código ou a descrição do serviço.')
        with self.lock:

            if self.requires_reindex:
                raise ValueError('O índice salvo é incompatível. Reimporte a planilha para gerar os vetores separados.')
            if not self.items:
                return []

            items = list(self.items)
            description_vectors = self.description_vectors.copy()
            context_vectors = self.context_vectors.copy()

        buscaEmbedding = self.embedder.embed([query])[0] # faz o embedding da busca

        if buscaEmbedding.shape[0] != description_vectors.shape[1]:
            raise ValueError('Vetores incompatíveis com o modelo atual. Reimporte a planilha.')

        # como os vetores são normalizados, o produto equivale à similaridade de cosseno
        description_scores = description_vectors @ buscaEmbedding
        context_scores = context_vectors @ buscaEmbedding
        scores = (self.description_weight * description_scores
                  + self.context_weight * context_scores)

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

        top = np.lexsort((-scores, -word_matches.astype(int), -exact_matches.astype(int)))[:nItens]

        return [dict(items[int(i)], similaridade=round(float(np.clip(scores[i], -1, 1)), 4),
                     similaridade_descricao=round(float(np.clip(description_scores[i], -1, 1)), 4),
                     similaridade_contexto=round(float(np.clip(context_scores[i], -1, 1)), 4),
                     correspondencia_palavras=bool(word_matches[i]),
                     correspondencia_exata=(bool(buscaNorm) and buscaNorm in (
                         normalized(items[int(i)]['codigo_servico']),
                         normalized(items[int(i)]['descricao_item'])))) for i in top]

        # retorna o codigo do serviço, a descrição, similaridade e se é 100%
