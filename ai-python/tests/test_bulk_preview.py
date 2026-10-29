import importlib
import numpy as np
import pytest
from fastapi.testclient import TestClient

api = importlib.import_module('ai-python.main')


class Embedder:
    name = 'bulk-preview-test'
    dimension = 2
    def embed(self, texts):
        return np.array([[len(text), 1] for text in texts], dtype=np.float32)


@pytest.mark.parametrize('populated', [False, True])
@pytest.mark.parametrize('text,activity,qualifiers,standardized,review', [
    ('SV PINTOR HORA EXTRA 50%', 'PINTOR', ['SV', 'HE 50%'], 'SV PINTOR HE 50%', False),
    ('HE 50%', '', ['HE 50%'], 'HE 50%', True),
    ('Manutenção de motor', 'Manutenção de motor', [], 'Manutenção de motor', False),
    ('PINTOR SEM HE', 'PINTOR SEM HE', [], 'PINTOR SEM HE', False),
])
def test_search_explains_query_even_with_empty_catalog(tmp_path, populated, text, activity, qualifiers, standardized, review):
    with TestClient(api.create_app(Embedder, tmp_path)) as client:
        if populated:
            assert client.post('/sync', json={'items': [{'codigo_servico': '01', 'descricao_item': text}], 'revision': 'db:1'}).status_code == 200
        response = client.post('/search', json={'query': text})
        assert response.status_code == 200
        data = response.json()
        assert data['query_parts'] == {'original': text, 'activity': activity, 'qualifiers': qualifiers,
                                      'standardized': standardized, 'needs_review': review,
                                      'dictionary_version': client.get('/status').json()['dictionary_version']}
        assert data['total'] == (1 if populated else 0)
        if populated:
            assert data['results'][0]['qualificadores'] == qualifiers
            assert client.get('/catalog').json()['items'][0]['descricao_item'] == text


def test_explanation_follows_dictionary_activation_and_reindex(tmp_path):
    with TestClient(api.create_app(Embedder, tmp_path)) as client:
        entries = [{'code': 'HE', 'meaning': 'hora extra', 'variants': ['HX'], 'active': True}]
        row = {'codigo_servico': '01', 'descricao_item': 'PINTOR HX 50%'}
        for version, active, qualifiers, activity in [('dict:1', True, ['HE 50%'], 'PINTOR'),
                                                      ('dict:2', False, [], 'PINTOR HX 50%')]:
            entries[0]['active'] = active
            assert client.put('/dictionary', json={'version': version, 'entries': entries}).status_code == 200
            assert client.post('/search', json={'query': row['descricao_item']}).status_code == 422
            assert client.post('/sync', json={'items': [row], 'revision': 'db:1'}).status_code == 200
            data = client.post('/search', json={'query': row['descricao_item']}).json()
            assert data['query_parts']['dictionary_version'] == version
            assert data['query_parts']['activity'] == activity
            assert data['query_parts']['qualifiers'] == data['results'][0]['qualificadores'] == qualifiers
            assert client.get('/catalog').json()['items'][0]['descricao_item'] == row['descricao_item']
