import importlib
import math
from copy import deepcopy

import numpy as np
import pytest
from fastapi.testclient import TestClient

core = importlib.import_module('ai-python.core')
api = importlib.import_module('ai-python.main')
comparison = importlib.import_module('ai-python.comparison')

DICTIONARY = {'version': 'draft-dictionary', 'entries': [
    {'code': 'HE', 'meaning': 'hora extra', 'variants': [], 'active': True},
    {'code': 'HNOR', 'meaning': 'hora normal', 'variants': [], 'active': True}]}


class ControlledEmbedder:
    name = 'comparison-test'
    dimension = 3

    def __init__(self):
        self.calls = []

    def embed(self, texts):
        self.calls.extend(texts)
        mapping = {'PINTOR': [1, 0, 0], 'PINTURA': [1, 0, 0],
                   'QUASE PINTOR': [math.sqrt(1 - .001 ** 2), .001, 0],
                   'ELETRICISTA': [0, 1, 0], 'PINTOR ELETRICISTA': [.6, .8, 0],
                   'PINTOR HE 50%': [.6, .8, 0],
                   'HE 50%': [0, 0, 1], 'HNOR': [0, 0, -1]}
        return np.array([mapping.get(text, [0, 1, 0]) for text in texts], dtype=np.float32)


def row(id, text):
    return {'catalog_id': id, 'codigo_servico': id, 'descricao_item': text}


def compare(catalog, item, candidates, dictionary=DICTIONARY):
    return catalog.compare(item, candidates, dictionary['entries'], dictionary['version'])


def test_scoped_candidates_exclude_self_and_unverified_catalog(tmp_path):
    catalog = core.Catalog(ControlledEmbedder(), tmp_path)
    catalog.replace([row('unverified', 'PINTOR'), row('verified', 'ELETRICISTA')], revision='base:1')
    result = compare(catalog, row('current', 'PINTURA'),
                     [row('current', 'PINTOR'), row('verified', 'ELETRICISTA')])
    assert [r['catalog_id'] for r in result['results']] == ['verified']
    assert result['results'][0]['similaridade_semantica'] == 0
    assert not result['results'][0]['semantic_exact']


def test_semantic_only_no_contains_or_code_exact_bonus(tmp_path):
    catalog = core.Catalog(ControlledEmbedder(), tmp_path)
    result = compare(catalog, row('current', 'PINTOR'),
                     [row('PINTOR', 'PINTOR ELETRICISTA'), row('semantic', 'PINTURA')])
    assert result['results'][0]['catalog_id'] == 'semantic'
    assert result['results'][0]['semantic_exact']
    assert result['results'][0]['similaridade_semantica'] == 1
    contains = result['results'][1]
    assert contains['similaridade_semantica'] == pytest.approx(.6)
    assert not contains['semantic_exact']


def test_no_rounding_before_automatic_decision(tmp_path):
    catalog = core.Catalog(ControlledEmbedder(), tmp_path)
    result = compare(catalog, row('current', 'PINTOR'), [row('near', 'QUASE PINTOR')])['results'][0]
    assert round(result['similaridade_semantica'], 4) == 1
    assert result['similaridade_semantica'] < 1 - comparison.SEMANTIC_EXACT_TOLERANCE
    assert not result['semantic_exact']


@pytest.mark.parametrize('query,weights,expected', [
    ('PINTURA HE 50%', {'activity': .9, 'qualifiers': .1}, [1, .9, .8, .1]),
    ('PINTURA', {'activity': 1., 'qualifiers': 0.}, [1, 1, 1, 0]),
    ('HE 50%', {'activity': 0., 'qualifiers': 1.}, [1, 1, 0, -1]),
])
def test_weights_match_activity_qualifier_strategy(tmp_path, query, weights, expected):
    catalog = core.Catalog(ControlledEmbedder(), tmp_path)
    candidates = [row('a', 'PINTOR HE 50%'), row('b', 'PINTOR'),
                  row('c', 'PINTOR HNOR'), row('d', 'ELETRICISTA HE 50%')]
    result = compare(catalog, row('current', query), candidates)
    assert result['weights'] == weights
    assert [r['similaridade_semantica'] for r in result['results']] == pytest.approx(expected)


def test_comparison_never_writes_or_mutates_permanent_catalog(tmp_path):
    embedder = ControlledEmbedder()
    catalog = core.Catalog(embedder, tmp_path)
    catalog.replace([row('saved', 'PINTOR HE 50%')], revision='base:1')
    before_files = {path.name: path.read_bytes() for path in tmp_path.iterdir()}
    before_items = [dict(item) for item in catalog.items]
    before_vectors = catalog.description_vectors.copy(), catalog.context_vectors.copy()
    before_dictionary = catalog.dictionary
    result = compare(catalog, row('current', 'PINTOR HE 50%'), [row('draft', 'PINTOR')],
                     {'version': 'isolated-empty', 'entries': []})
    assert result['weights'] == {'activity': 1., 'qualifiers': 0.}
    assert result['results'][0]['similaridade_semantica'] == pytest.approx(.6)
    assert catalog.items == before_items and catalog.revision == 'base:1'
    assert catalog.dictionary is before_dictionary and not catalog.requires_reindex
    np.testing.assert_array_equal(catalog.description_vectors, before_vectors[0])
    np.testing.assert_array_equal(catalog.context_vectors, before_vectors[1])
    assert {path.name: path.read_bytes() for path in tmp_path.iterdir()} == before_files


def test_cache_reuses_verified_embeddings_and_permanent_vectors(tmp_path):
    embedder = ControlledEmbedder()
    catalog = core.Catalog(embedder, tmp_path)
    catalog.replace([row('verified', 'PINTOR HE 50%')], revision='base:1')
    embedder.calls.clear()
    result = compare(catalog, row('current', 'PINTURA HE 50%'), [row('verified', 'PINTOR HE 50%')])
    assert embedder.calls == ['PINTURA']
    assert result['results'][0]['semantic_exact']
    embedder.calls.clear()
    compare(catalog, row('next', 'PINTURA HE 50%'), [row('verified', 'PINTOR HE 50%')])
    assert embedder.calls == []


def test_uses_all_candidates_before_truncating_and_preserves_input(tmp_path):
    catalog = core.Catalog(ControlledEmbedder(), tmp_path)
    candidates = [row(str(i), 'ELETRICISTA') for i in range(25)] + [row('last', 'PINTURA')]
    result = compare(catalog, row('current', 'PINTOR'), candidates)
    assert len(result['results']) == 20
    assert result['results'][0]['catalog_id'] == 'last'
    assert result['results'][0]['semantic_exact']
    assert len(candidates) == 26 and 'semantic_exact' not in candidates[-1]


def test_bounded_cache_does_not_evict_texts_needed_by_current_request():
    service = comparison.ScopedComparison(ControlledEmbedder(), max_cached_texts=1)
    result = service.compare(row('current', 'PINTOR'),
                             [row('a', 'PINTURA'), row('b', 'ELETRICISTA')],
                             DICTIONARY['entries'], DICTIONARY['version'], .9, .1)
    assert result['results'][0]['semantic_exact']
    assert len(service.cache) == 1


def test_empty_candidates_or_empty_activity_never_force_exact(tmp_path):
    catalog = core.Catalog(ControlledEmbedder(), tmp_path)
    assert compare(catalog, row('current', 'PINTOR'), [])['results'] == []
    punctuation = compare(catalog, row('current', '---'), [row('other', '---')])
    assert punctuation['weights'] == {'activity': 0., 'qualifiers': 0.}
    assert not punctuation['results'][0]['semantic_exact']


def test_api_validation_and_isolation(tmp_path):
    with TestClient(api.create_app(ControlledEmbedder, tmp_path)) as client:
        before = client.get('/status').json()
        payload = {'item': row('current', 'PINTOR'), 'candidates': [row('verified', 'PINTURA')],
                   'dictionary': deepcopy(DICTIONARY)}
        response = client.post('/compare', json=payload)
        assert response.status_code == 200 and response.json()['results'][0]['semantic_exact']
        assert response.json()['model'] == ControlledEmbedder.name
        assert client.get('/status').json() == before
        assert client.get('/catalog').json() == {'items': []}
        assert not list(tmp_path.iterdir())
        assert client.post('/compare', json={}).status_code == 422
        payload['item']['descricao_item'] = 'x' * 301
        assert client.post('/compare', json=payload).status_code == 422
        payload['item']['descricao_item'] = 'PINTOR'
        payload['candidates'] *= 2
        assert client.post('/compare', json=payload).status_code == 422
        payload['candidates'] = [row('verified', 'PINTURA')]
        payload['dictionary']['entries'] = [
            {'code': 'A', 'meaning': 'pintor'}, {'code': 'B', 'meaning': 'pintor'}]
        assert client.post('/compare', json=payload).status_code == 422


def test_invalid_encoder_values_are_rejected(tmp_path):
    embedder = ControlledEmbedder()
    embedder.embed = lambda texts: np.full((len(texts), 3), np.nan)
    catalog = core.Catalog(embedder, tmp_path)
    with pytest.raises(ValueError, match='vetores inválidos'):
        compare(catalog, row('current', 'PINTOR'), [row('other', 'PINTURA')])
