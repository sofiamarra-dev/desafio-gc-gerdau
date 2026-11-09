"""A remoção automática exige igualdade cadastral, nunca uma nota semântica."""
import importlib
from copy import deepcopy

import numpy as np
import pytest
from fastapi.testclient import TestClient

module = importlib.import_module('ai-python.identical_records')
comparison = importlib.import_module('ai-python.comparison')
api = importlib.import_module('ai-python.main')

DICTIONARY = {'version': 'identity-scan:1', 'entries': [
    {'code': 'SV', 'meaning': 'serviço', 'variants': [], 'active': True},
    {'code': 'HE', 'meaning': 'hora extra', 'variants': ['H.EXTRA'], 'active': True}]}


def row(identifier, description='PINTOR', **fields):
    return {'catalog_id': identifier, 'codigo_servico': identifier,
            'descricao_item': description, **fields}


def scan(rows):
    return module.find_identical_records(rows, DICTIONARY['entries'], DICTIONARY['version'])


class CollisionEncoder:
    name = 'identity-scan-test'
    dimension = 2
    def embed(self, texts):
        return np.tile(np.array([1., 0.], dtype=np.float32), (len(texts), 1))


class NoEmbedding(CollisionEncoder):
    def embed(self, texts):
        raise AssertionError('A varredura de identidade não pode gerar embeddings.')


def test_same_embedding_does_not_mean_identical_record():
    rows = [row('a', 'PINTOR'), row('b', 'PINTURA')]
    result = comparison.ScopedComparison(CollisionEncoder()).compare(
        rows[0], rows[1:], DICTIONARY['entries'], DICTIONARY['version'], .9, .1)
    assert result['results'][0]['similaridade_semantica'] == 1
    assert scan(rows) == {'groups': [], 'total_removed': 0}


def test_reuses_existing_standardization_without_losing_original_records():
    rows = [row('a', 'SV PINTOR HORA EXTRA 50%'), row('b', '  sv pintor H.EXTRA 50%  ')]
    before = deepcopy(rows)
    assert scan(rows) == {'groups': [{'kept_id': 'a', 'duplicate_ids': ['b']}], 'total_removed': 1}
    assert rows == before


@pytest.mark.parametrize('field', module.CLEANUP_IDENTITY_FIELDS)
def test_missing_or_different_metadata_cannot_be_a_wildcard(field):
    left, right = row('a', **{field: 'VALOR'}), row('b')
    assert scan([left, right])['total_removed'] == 0
    right[field] = 'OUTRO'
    assert scan([left, right])['total_removed'] == 0


@pytest.mark.parametrize('supplier', ['F2', '', None])
def test_cleanup_ignores_different_or_absent_supplier_without_mutating_records(supplier):
    rows = [row('a', 'SV UNIÃO SOLDÁVEL 25 MM', codigo_fornecedor='F1'),
            row('b', 'SV UNIÃO SOLDÁVEL 25 MM', codigo_fornecedor=supplier)]
    before = deepcopy(rows)
    assert scan(rows) == {'groups': [{'kept_id':'a','duplicate_ids':['b']}], 'total_removed':1}
    assert rows == before


def test_bulk_identity_still_distinguishes_suppliers():
    rows = [row('a', codigo_fornecedor='F1'), row('b', codigo_fornecedor='F2')]
    result = comparison.ScopedComparison(CollisionEncoder()).compare(
        rows[0], rows[1:], DICTIONARY['entries'], DICTIONARY['version'], .9, .1)
    assert not result['results'][0]['identical_record']
    assert scan(rows)['total_removed'] == 1


@pytest.mark.parametrize('different', ['PINTOR HE 70%', 'PINTOR HE', 'PINTOR!', 'PINTOR PINTOR'])
def test_description_details_are_preserved(different):
    assert scan([row('a', 'PINTOR HE 50%'), row('b', different)])['groups'] == []


def test_descriptions_requiring_review_are_never_removed_automatically():
    assert scan([row('a', 'HE'), row('b', 'HORA EXTRA')])['groups'] == []


def test_keeper_prefers_verified_then_partial_then_oldest_and_id():
    rows = [row('old', cleanup_passes='0', required_cleanup_passes='1', created_at='2020-01-01T00:00:00Z'),
            row('partial', cleanup_passes='1', required_cleanup_passes='2', created_at='2022-01-01T00:00:00Z'),
            row('verified', cleanup_passes='1', required_cleanup_passes='1', created_at='2025-01-01T00:00:00Z')]
    assert scan(rows)['groups'][0]['kept_id'] == 'verified'
    assert scan(rows[:2])['groups'][0]['kept_id'] == 'partial'
    for sequence in (rows, list(reversed(rows))):
        assert scan(sequence)['groups'][0]['kept_id'] == 'verified'
    assert scan([row('b'), row('a')])['groups'][0]['kept_id'] == 'a'


def test_created_timestamp_is_compared_as_an_instant_not_text():
    rows = [row('later', created_at='2026-01-01T09:00:00-03:00'),
            row('earlier', created_at='2026-01-01T10:00:00+04:00')]
    assert scan(rows)['groups'][0]['kept_id'] == 'earlier'


def test_all_duplicates_are_reported_without_top_twenty_limit():
    rows = [row(str(i)) for i in range(80)]
    result = scan(rows)
    assert result['total_removed'] == 79
    assert len(result['groups']) == 1
    assert len(result['groups'][0]['duplicate_ids']) == 79


def test_absent_or_duplicate_ids_are_rejected():
    with pytest.raises(ValueError):
        scan([row('a'), row('a')])
    with pytest.raises(ValueError):
        scan([row('')])


def test_http_scan_keeps_metadata_and_returns_every_group_without_model_or_index_changes(tmp_path):
    rows = [row(f'{i}-old', f'ATIVIDADE {i}', codigo_fornecedor='F1', created_at='2020-01-01T00:00:00Z')
            for i in range(30)]
    rows += [row(f'{i}-verified', f'ATIVIDADE {i}', codigo_fornecedor='F2', cleanup_passes='2',
                 required_cleanup_passes='2', created_at='2026-01-01T00:00:00Z') for i in range(30)]
    with TestClient(api.create_app(NoEmbedding, tmp_path)) as client:
        response = client.post('/identical-records', json={'items': rows, 'dictionary': DICTIONARY})
        assert response.status_code == 200, response.text
        result = response.json()
        assert result['total_removed'] == 30 and len(result['groups']) == 30
        assert all(group['kept_id'].endswith('-verified') for group in result['groups'])
        assert client.get('/status').json()['size'] == 0
        assert client.get('/catalog').json()['items'] == []
        invalid = client.post('/identical-records', json={'items': [row('a'), row('a')], 'dictionary': DICTIONARY})
        assert invalid.status_code == 422
