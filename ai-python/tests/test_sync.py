import importlib

import numpy as np
from fastapi.testclient import TestClient

core = importlib.import_module('ai-python.core')
api = importlib.import_module('ai-python.main')


class ControlledEmbedder:
    name = 'sync-test-only'
    dimension = 2

    def __init__(self):
        self.calls = []

    def embed(self, texts):
        self.calls.extend(texts)
        return core.unit_vector(np.array([[len(text) + 1, 1] for text in texts], dtype=np.float32))


def test_sync_only_embeds_new_text_and_preserves_ids(tmp_path):
    embedder = ControlledEmbedder()
    catalog = core.Catalog(embedder, tmp_path)
    first = dict(catalog_id='a', codigo_servico='001', descricao_item='Manutenção de motor', grupo_servico='Elétrica')
    catalog.replace([first], revision='db:1', allow_empty=True)
    assert len(embedder.calls) == 1
    second = dict(catalog_id='b', codigo_servico='002', descricao_item='Limpeza', grupo_servico='Elétrica')
    catalog.replace([first, second], revision='db:2', allow_empty=True)
    assert embedder.calls[1:] == ['Limpeza']
    restored_embedder = ControlledEmbedder()
    restored = core.Catalog(restored_embedder, tmp_path)
    assert restored.revision == 'db:2'
    assert restored.search('001')[0]['catalog_id'] == 'a'
    restored_embedder.calls.clear()
    restored.replace([first, second], revision='db:2', allow_empty=True)
    assert restored_embedder.calls == []


def test_sync_empty_and_api(tmp_path):
    with TestClient(api.create_app(ControlledEmbedder, tmp_path)) as client:
        assert client.post('/sync', json={'items': [], 'revision': 'empty:0'}).status_code == 200
        assert client.get('/status').json()['revision'] == 'empty:0'
        assert client.post('/search', json={'query': 'motor'}).json()['results'] == []
        row = dict(catalog_id='x', codigo_servico='M', descricao_item='Manutenção de motor')
        assert client.post('/sync', json={'items': [row], 'revision': 'empty:1'}).status_code == 200
        assert client.get('/catalog').json()['items'][0]['catalog_id'] == 'x'
        result = client.post('/search', json={'query': 'manutencao'}).json()['results'][0]
        assert result['correspondencia_palavras'] is True
        assert result['catalog_id'] == 'x'
    restored = core.Catalog(ControlledEmbedder(), tmp_path)
    assert restored.revision == 'empty:1'
