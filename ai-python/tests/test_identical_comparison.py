"""Colisões deliberadas de embeddings nunca podem significar igualdade cadastral."""
import importlib
from copy import deepcopy

import numpy as np
import pytest
from fastapi.testclient import TestClient

comparison = importlib.import_module('ai-python.comparison')
api = importlib.import_module('ai-python.main')

DICTIONARY = {'version': 'identical-tests', 'entries': [
    {'code': 'HE', 'meaning': 'hora extra', 'variants': ['H.EXTRA'], 'active': True},
    {'code': 'SV', 'meaning': 'serviço', 'variants': [], 'active': True}]}


class CollidingEmbedder:
    name = 'deliberately-colliding-test-embeddings'
    dimension = 2

    def embed(self, texts):
        # Todos os textos distintos têm o mesmo embedding. A nota sozinha
        # seria insuficiente para decidir qualquer uma destas equivalências.
        return np.tile(np.array([1., 0.], dtype=np.float32), (len(texts), 1))


def row(id, description, **fields):
    return {'catalog_id': id, 'codigo_servico': id, 'descricao_item': description, **fields}


def compare(item, candidates):
    return comparison.ScopedComparison(CollidingEmbedder()).compare(
        item, candidates, DICTIONARY['entries'], DICTIONARY['version'], .9, .1)['results']


@pytest.mark.parametrize('description', [
    'PINTOR HE 50%', 'PINTOR HE 70%', 'PINTOR SEM ADICIONAL',
    'PINTOR ELETRICISTA', 'PINTOR PINTOR', 'ELETRICISTA',
])
def test_same_embedding_different_complete_descriptions_are_not_identical(description):
    result = compare(row('current', 'PINTOR'), [row('candidate', description)])[0]
    assert result['similaridade_semantica'] == 1
    assert result['semantic_exact']
    assert not result['identical_record']


@pytest.mark.parametrize('left,right', [
    ('PINTOR HE 50%', 'PINTOR HE 70%'),
    ('PINTOR HE 50%', 'PINTOR HE'),
    ('CORTE SEM ÁGUA', 'CORTE COM ÁGUA'),
    ('TUBO -5 MM', 'TUBO 5 MM'),
    ('TUBO 1.5 MM', 'TUBO 15 MM'),
    ('TUBO 1/2', 'TUBO 12'),
    ('TUBO 20 CM', 'TUBO 20 MM'),
    ('MAÇÃ', 'MACA'),
    ('PINTOR!', 'PINTOR'),
    ('INSTALAÇÃO DE TUBO', 'TUBO DE INSTALAÇÃO'),
])
def test_significant_details_are_preserved(left, right):
    assert not compare(row('current', left), [row('candidate', right)])[0]['identical_record']


@pytest.mark.parametrize('field', comparison.IDENTITY_FIELDS)
@pytest.mark.parametrize('candidate_value', ['OUTRO', ''])
def test_different_or_missing_metadata_is_not_a_wildcard(field, candidate_value):
    left = row('current', 'PINTOR', **{field: 'VALOR'})
    right = row('candidate', 'PINTOR', **{field: candidate_value})
    result = compare(left, [right])[0]
    assert result['semantic_exact'] and not result['identical_record']


@pytest.mark.parametrize('description', [
    'PINTOR HE 50%', '  pintor   HE 50%  ', 'PINTOR hora extra 50%', 'PINTOR H.EXTRA 50%',
])
def test_complete_standardized_description_case_and_spaces_can_match(description):
    result = compare(row('current', 'PINTOR HE 50%'), [row('different-code', description)])[0]
    assert result['identical_record']


def test_unicode_composition_and_metadata_case_spaces_are_normalized():
    left = row('a', 'MANUTENÇÃO', unidade_medida='H', codigo_fornecedor='  FORN  001 ')
    right = row('b', 'manutenc\u0327a\u0303o', unidade_medida=' h ', codigo_fornecedor='forn 001')
    assert compare(left, [right])[0]['identical_record']


@pytest.mark.parametrize('description', ['HE 50%', 'SV', '---'])
def test_description_without_activity_never_auto_matches(description):
    assert not compare(row('a', description), [row('b', description)])[0]['identical_record']


def test_identical_candidate_is_prioritized_before_truncation_among_collisions():
    candidates = [row(str(i), 'PINTURA') for i in range(25)] + [row('last', 'PINTOR')]
    before = deepcopy(candidates)
    result = compare(row('current', 'PINTOR'), candidates)
    assert len(result) == 20 and result[0]['catalog_id'] == 'last'
    assert result[0]['identical_record']
    assert all(not candidate['identical_record'] for candidate in result[1:])
    assert candidates == before


def test_api_returns_identity_separately_from_100_percent_embeddings(tmp_path):
    with TestClient(api.create_app(CollidingEmbedder, tmp_path)) as client:
        response = client.post('/compare', json={
            'item': row('current', 'PINTOR'),
            'candidates': [row('self-like', 'PINTOR HE 50%'), row('identical', 'pintor')],
            'dictionary': DICTIONARY,
        })
        assert response.status_code == 200
        identical, different = response.json()['results']
        assert identical['identical_record'] and identical['catalog_id'] == 'identical'
        assert different['semantic_exact'] and not different['identical_record']
        assert client.get('/catalog').json() == {'items': []}
        assert not list(tmp_path.iterdir())
