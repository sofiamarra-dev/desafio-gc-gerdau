"""Indexação e busca top-k com armazenamento local simples (sem SAP no MVP)."""
import json
import os
import tempfile
import threading
from pathlib import Path

import numpy as np

from .embedder import normalized, unit_vector


FIELDS = ['grupo_servico', 'codigo_fornecedor', 'codigo_servico', 'descricao_item', 'unidade_medida', 'unidade_descricao']


def passage(item: dict) -> str:

    description = item.get('descricao_item', '')
    group = item.get('grupo_servico', '')
    unit = item.get('unidade_descricao', '') or item.get('unidade_medida', '')
    return f'Serviço: {description}. Grupo: {group}. Unidade: {unit}.'


class Catalog:
    def __init__(self, embedder, data_dir: Path):
        self.embedder = embedder
        self.data_dir = data_dir
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.items: list[dict] = []
        self.vectors = np.zeros((0, 0), dtype=np.float32)
        self.lock = threading.RLock()
        self._load()

    def _load(self):
        json_file, npy_file = self.data_dir / 'catalog.json', self.data_dir / 'vectors.npy'
        if json_file.exists() and npy_file.exists():
            try:
                contents = json.loads(json_file.read_text(encoding='utf-8'))
                if contents.get('embedder') != self.embedder.name: # aqui impede de comparar vetores gerados por outro modelo
                    return

                loaded = np.load(npy_file, allow_pickle=False) # lê a informação do arquivo de embeddings
                if loaded.ndim == 2 and len(contents['items']) == len(loaded): # verifica se carregou corretamente
                    self.items = contents['items']
                    self.vectors = unit_vector(loaded)
            except (OSError, ValueError, KeyError):
                pass

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

        texts = []

        for item in registros:
            texto = passage(item)
            texts.append(texto)

        vectors = self.embedder.embed(texts) # aqui transforma o texto no vetor de embeddings

        vectors = unit_vector(vectors) # aqui normaliza os vetores pra facilitar o calculo

        with self.lock:

            with tempfile.NamedTemporaryFile(mode='wb', dir=self.data_dir, delete=False) as f: # cria um arquivo para armazenar os vetores
                np.save(f, vectors)
                tmp_npy = Path(f.name)

            with tempfile.NamedTemporaryFile(mode='w', dir=self.data_dir, delete=False, encoding='utf-8') as f: # também cria um arquivo, mas para armazenar os serviços
                json.dump({'embedder': self.embedder.name, 'items': registros}, f, ensure_ascii=False)
                tmp_json = Path(f.name)

            # aqui subistitui os arquivos antigos pelos novos com as informações novas
            os.replace(tmp_npy, self.data_dir / 'vectors.npy')
            os.replace(tmp_json, self.data_dir / 'catalog.json')

            self.items = registros
            self.vectors = vectors

        return len(registros)

    def search(self, query: str, limit: int = 20) -> list[dict]: #metodo que retorna a lista dos mais proximos

        if not query.strip(): # verifica se a busca é valida
            raise ValueError('Informe o código ou a descrição do serviço.')
        with self.lock:

            if not self.items:
                return []

            items = list(self.items)
            vectors = self.vectors.copy()

        buscaEmbedding = self.embedder.embed([query])[0] # faz o embedding da busca

        if buscaEmbedding.shape[0] != vectors.shape[1]:
            raise ValueError('Vetores incompatíveis com o modelo atual. Reimporte a planilha.')

        scores = vectors @ buscaEmbedding # faz a multiplicação dos vetores da busca e dos serviços

        buscaNorm = normalized(query)

        exact_matches = np.zeros(len(items), dtype=bool)

        for i, item in enumerate(items):
            if buscaNorm and buscaNorm in (normalized(item['codigo_servico']), normalized(item['descricao_item'])):
                exact_matches[i] = True
                scores[i] = 1.0
        nItens = min(max(1, limit), 20, len(items))

        top = np.lexsort((-scores, -exact_matches.astype(int)))[:nItens]

        return [dict(items[int(i)], similaridade=round(float(np.clip(scores[i], -1, 1)), 4),
                     correspondencia_exata=(bool(buscaNorm) and buscaNorm in (
                         normalized(items[int(i)]['codigo_servico']),
                         normalized(items[int(i)]['descricao_item'])))) for i in top]

        # retorna o codigo do serviço, a descrição, similaridade e se é 100%
