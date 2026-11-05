import importlib
import numpy as np

comparison = importlib.import_module('ai-python.comparison')


class ControlledEmbedder:
    name = 'quick-test'
    dimension = 2

    def embed(self, texts):
        return np.array([[1, 0] if text == 'PINTOR' else [0, 1]
                         if text == 'PINTOR ELETRICISTA' else [.8, .6]
                         for text in texts], dtype=np.float32)


def compare(query, rows):
    return comparison.ScopedComparison(ControlledEmbedder()).compare(
        {'descricao_item': query}, rows, [], 'quick-test', .9, .1)


def test_contains_ignores_accents_and_requires_whole_words_without_changing_score():
    result = compare('Píntor', [{'catalog_id': 'a', 'descricao_item': 'PINTOR ELETRICISTA'}])
    assert result['results'][0]['correspondencia_palavras']
    assert result['quick_creation_check']['requires_review']
    other = compare('PINT', [{'catalog_id': 'a', 'descricao_item': 'PINTOR ELETRICISTA'}])
    assert not other['results'][0]['correspondencia_palavras']


def test_contains_outside_the_top_twenty_still_blocks_automatic_creation():
    rows = [{'catalog_id': str(i), 'descricao_item': f'ATIVIDADE {i}'} for i in range(20)]
    rows.append({'catalog_id': 'last', 'descricao_item': 'PINTOR ELETRICISTA'})
    result = compare('PINTOR', rows)
    assert len(result['results']) == 20
    assert all(not row['correspondencia_palavras'] and row['similaridade_semantica'] < .9
               for row in result['results'])
    assert result['quick_creation_check'] == {'version': 'quick-creation-1', 'candidate_count': 21,
                                             'requires_review': True}


def test_no_correspondence_permits_fast_creation_and_semantic_high_match_blocks_it():
    low = compare('PINTOR', [{'catalog_id': 'low', 'descricao_item': 'ATIVIDADE'}])
    assert not low['quick_creation_check']['requires_review']
    high = compare('ATIVIDADE', [{'catalog_id': 'high', 'descricao_item': 'OUTRA ATIVIDADE'}])
    assert high['quick_creation_check']['requires_review']
    empty = compare('PINTOR', [])
    assert empty['quick_creation_check']['candidate_count'] == 0
    assert not empty['quick_creation_check']['requires_review']
