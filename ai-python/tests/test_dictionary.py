import importlib
import numpy as np
import pytest
from fastapi.testclient import TestClient

core = importlib.import_module('ai-python.core')
api = importlib.import_module('ai-python.main')


class Embedder:
    dimension = 2
    name = 'dictionary-test'
    def __init__(self): self.calls = []
    def embed(self, texts):
        self.calls.extend(texts)
        return np.array([[len(text), 1] for text in texts], dtype=np.float32)


def entries(active=True):
    return [{'code': 'HE', 'meaning': 'hora extra', 'variants': ['horas extras', 'HX'], 'active': active}]


def test_dictionary_change_invalidates_vectors_cache_and_persists(tmp_path):
    embedder = Embedder()
    catalog = core.Catalog(embedder, tmp_path)
    rows = [{'codigo_servico': '01', 'descricao_item': 'PINTOR HX 50%'}]
    catalog.replace(rows, revision='db:1')
    assert catalog.items[0]['atividade'] == 'PINTOR HX 50%'
    catalog.update_dictionary(entries(), 'dict:1')
    assert catalog.requires_reindex
    assert catalog.revision == 'db:1'
    assert catalog.description_vectors.size == 0
    with pytest.raises(ValueError): catalog.search('pintor')
    restarted = core.Catalog(Embedder(), tmp_path)
    assert restarted.requires_reindex and restarted.dictionary.version == 'dict:1'
    restarted.replace(rows, revision='db:1')
    assert restarted.items[0]['atividade'] == 'PINTOR'
    assert restarted.items[0]['qualificadores'] == ['HE 50%']
    loaded = core.Catalog(Embedder(), tmp_path)
    assert not loaded.requires_reindex
    loaded.update_dictionary(entries(), 'dict:1')
    assert not loaded.requires_reindex
    with pytest.raises(ValueError): loaded.update_dictionary(entries(False), 'dict:1')
    assert not loaded.requires_reindex
    loaded.update_dictionary(entries(False), 'dict:2')
    loaded.replace(rows, revision='db:1')
    assert loaded.items[0]['atividade'] == 'PINTOR HX 50%'


def test_preview_never_mutates_saved_dictionary_or_index_and_protects_negation(tmp_path):
    with TestClient(api.create_app(Embedder, tmp_path)) as client:
        before = client.get('/status').json()
        payload = {'version': 'dict:1', 'entries': entries(), 'text': 'PINTOR HX 50%'}
        assert client.post('/dictionary/preview', json=payload).json()['qualifiers'] == ['HE 50%']
        assert client.get('/status').json() == before
        assert not (tmp_path / 'dictionary.json').exists()
        payload['text'] = 'PINTOR SEM HX'
        preview = client.post('/dictionary/preview', json=payload).json()
        assert preview['activity'] == 'PINTOR SEM HX' and preview['qualifiers'] == []
        assert client.put('/dictionary', json=payload).status_code == 200
        assert client.get('/status').json()['dictionary_version'] == 'dict:1'


def test_inactive_ownership_and_invalid_input_rejected(tmp_path):
    with TestClient(api.create_app(Embedder, tmp_path)) as client:
        payload = {'version': 'bad:1', 'entries': entries(False) + [
            {'code': 'ZZ', 'meaning': 'outro', 'variants': ['HÓRA   EXTRA'], 'active': True}]}
        assert client.put('/dictionary', json=payload).status_code == 422
        assert client.get('/status').json()['dictionary_version'] == 'search-8'
        payload['entries'] = entries()
        payload['entries'][0]['variants'] = ['a' * 81]
        assert client.put('/dictionary', json=payload).status_code == 422
