import importlib
import json

import numpy as np
import pytest
from fastapi.testclient import TestClient

core = importlib.import_module('ai-python.core')
api = importlib.import_module('ai-python.main')
abbreviations = importlib.import_module('ai-python.abbreviations')


class SemanticEmbedder:
    name = 'weighted-test'
    dimension = 3

    def __init__(self):
        self.calls = []

    def embed(self, texts):
        self.calls.extend(texts)
        mapping = {'PINTOR': [1, 0, 0], 'PINTURA': [1, 0, 0],
                   'ELETRICISTA': [0, 1, 0], 'HE 50%': [0, 0, 1], 'HNOR': [0, 0, -1]}
        return np.array([mapping.get(text, [1, 0, 0]) for text in texts], dtype=np.float32)


def rows():
    return [dict(catalog_id='a', codigo_servico='01', descricao_item='PINTOR HE 50%', grupo_servico='IGNORAR'),
            dict(catalog_id='b', codigo_servico='02', descricao_item='PINTOR HNOR'),
            dict(catalog_id='c', codigo_servico='03', descricao_item='ELETRICISTA HE 50%'),
            dict(catalog_id='d', codigo_servico='04', descricao_item='PINTOR')]


def test_query_and_catalog_use_two_fields_and_correct_weights(tmp_path):
    embedder = SemanticEmbedder()
    catalog = core.Catalog(embedder, tmp_path)
    catalog.replace(rows(), revision='db:1')
    assert '' not in embedder.calls
    assert not any('IGNORAR' in text for text in embedder.calls)
    embedder.calls.clear()
    results = catalog.search('PINTURA HE 50%')
    assert embedder.calls == ['PINTURA', 'HE 50%']
    assert [row['catalog_id'] for row in results] == ['a', 'd', 'b', 'c']
    assert [row['similaridade_semantica'] for row in results] == [1.0, .9, .8, .1]
    assert results[0]['atividade'] == 'PINTOR'
    assert results[0]['qualificadores'] == ['HE 50%']
    assert results[0]['descricao_item'] == 'PINTOR HE 50%'
    assert results[0]['pesos_aplicados'] == {'atividade': .9, 'qualificadores': .1}


def test_missing_query_field_uses_only_available_field(tmp_path):
    catalog = core.Catalog(SemanticEmbedder(), tmp_path)
    catalog.replace(rows())
    plain = {row['catalog_id']: row for row in catalog.search('PINTURA')}
    assert all(plain[id]['similaridade_semantica'] == 1.0 for id in ('a', 'b', 'd'))
    assert plain['a']['pesos_aplicados'] == {'atividade': 1.0, 'qualificadores': 0.0}
    qualifiers = catalog.search('HE 50%')
    assert qualifiers[0]['pesos_aplicados'] == {'atividade': 0.0, 'qualificadores': 1.0}
    assert qualifiers[0]['similaridade_semantica'] == 1.0
    punctuation = catalog.search('(HE 50%)')
    assert punctuation[0]['pesos_aplicados'] == {'atividade': 0.0, 'qualificadores': 1.0}


def test_metadata_changes_do_not_reencode_text_and_reload_preserves_semantics(tmp_path):
    embedder = SemanticEmbedder()
    catalog = core.Catalog(embedder, tmp_path)
    catalog.replace(rows(), revision='db:1')
    embedder.calls.clear()
    edited = rows()
    edited[0].update(grupo_servico='ALTERADO', unidade_medida='MES')
    catalog.replace(edited, revision='db:2')
    assert embedder.calls == []
    reloaded = core.Catalog(SemanticEmbedder(), tmp_path)
    assert not reloaded.requires_reindex
    assert reloaded.search('PINTURA HE 50%') == catalog.search('PINTURA HE 50%')


@pytest.mark.parametrize('format_version', [2, 3])
def test_legacy_index_preserves_records_but_never_reuses_old_vectors(tmp_path, format_version):
    (tmp_path / 'catalog.json').write_text(json.dumps({'index_format': format_version,
        'embedder': SemanticEmbedder.name, 'revision': 'db:3', 'items': rows()}), encoding='utf-8')
    np.savez(tmp_path / 'vectors.npz', description=np.ones((4, 3)), context=np.ones((4, 3)))
    embedder = SemanticEmbedder()
    catalog = core.Catalog(embedder, tmp_path)
    assert catalog.requires_reindex and catalog.revision == 'db:3' and len(catalog.items) == 4
    with pytest.raises(ValueError):
        catalog.search('PINTURA')
    catalog.replace(rows(), revision='db:3')
    assert set(embedder.calls) == {'PINTOR', 'ELETRICISTA', 'HE 50%', 'HNOR'}
    assert not catalog.requires_reindex


@pytest.mark.parametrize('field,value', [('dictionary_version', 'old'), ('generation', 'different')])
def test_incompatible_metadata_requires_rebuild(tmp_path, field, value):
    catalog = core.Catalog(SemanticEmbedder(), tmp_path)
    catalog.replace(rows(), revision='db:1')
    path = tmp_path / 'catalog.json'
    contents = json.loads(path.read_text(encoding='utf-8'))
    contents[field] = value
    path.write_text(json.dumps(contents), encoding='utf-8')
    reloaded = core.Catalog(SemanticEmbedder(), tmp_path)
    assert reloaded.requires_reindex and len(reloaded.items) == 4


def test_api_exports_legacy_rows_and_reports_new_strategy_after_sync(tmp_path):
    (tmp_path / 'catalog.json').write_text(json.dumps({'index_format': 3,
        'embedder': SemanticEmbedder.name, 'revision': 'db:3', 'items': rows()}), encoding='utf-8')
    with TestClient(api.create_app(SemanticEmbedder, tmp_path)) as client:
        status = client.get('/status').json()
        assert status['requires_reindex'] and not status['ready'] and status['size'] == 4
        assert client.get('/catalog').json()['items'] == rows()
        assert client.post('/sync', json={'items': rows(), 'revision': 'db:3'}).status_code == 200
        status = client.get('/status').json()
        assert status['ready'] and status['weights'] == {'activity': .9, 'qualifiers': .1}
        result = client.post('/search', json={'query': 'PINTURA HE 50%'}).json()['results'][0]
        assert result['qualificadores'] == ['HE 50%']
        assert result['similaridade_semantica'] == 1.0


@pytest.mark.parametrize('text,activity', [
    ('ASG (SEM INSALUBRIDADE)', 'ASG (SEM INSALUBRIDADE)'),
    ('SV PINTOR SEM HE', 'PINTOR SEM HE'),
    ('PINTOR SEM ADICIONAL DE PERICULOSIDADE', 'PINTOR SEM ADICIONAL DE PERICULOSIDADE'),
    ('LÍDER DE SV SENIOR', 'LÍDER DE SV SENIOR'),
    ('AUX ADM H ESP HE 50%', 'AUX ADM H ESP'),
])
def test_conservative_activity_handling(text, activity):
    assert core.description_text({'descricao_item': text}) == activity


def test_exact_code_and_contains_priorities_still_work(tmp_path):
    catalog = core.Catalog(SemanticEmbedder(), tmp_path)
    catalog.replace(rows())
    assert catalog.search('02')[0]['correspondencia_exata']
    assert catalog.search('PINTOR')[0]['correspondencia_palavras']
