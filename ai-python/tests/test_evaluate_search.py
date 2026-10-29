import importlib
import json
import numpy as np
import pytest

evaluation = importlib.import_module('ai-python.evaluate_search')


class Embedder:
    name = 'evaluation-test'
    dimension = 2
    def embed(self, texts):
        return np.array([[1., len(text)] for text in texts], dtype=np.float32)


def test_control_metrics_accept_alternatives_and_do_not_call_hit_recall():
    result = evaluation.technical_metrics([{'target_rank':1},{'target_rank':5},{'target_rank':None}])
    assert result['hit@1'] == pytest.approx(1 / 3)
    assert result['hit@5'] == result['hit@20'] == pytest.approx(2 / 3)
    assert result['mrr@20'] == pytest.approx((1 + 1/5) / 3)
    assert evaluation.technical_metrics([])['hit@1'] is None
    assert evaluation.is_target({'codigo_servico':'02','descricao_item':'qualquer'}, {'expected_codes':['01','02']})
    assert evaluation.is_target({'codigo_servico':'99','descricao_item':'Maçã'}, {'expected_description':'MACA'})
    assert not evaluation.is_target({'codigo_servico':'01','descricao_item':'Pintor'}, {'candidate_codes':['01']})


def test_snapshot_does_not_overwrite_another_evaluation_or_source(tmp_path):
    source, output = tmp_path/'source', tmp_path/'snapshot'
    source.mkdir()
    for name in evaluation.FILES: (source/name).write_text(name,encoding='utf-8')
    hashes = evaluation.snapshot(source, output)
    assert hashes == evaluation.snapshot(source, output)
    (output/'catalog.json').write_text('other',encoding='utf-8')
    with pytest.raises(ValueError): evaluation.snapshot(source, output)
    assert (output/'catalog.json').read_text() == 'other'
    assert hashes == {name:evaluation.digest(source/name) for name in evaluation.FILES}


def test_identity_changes_when_model_dependencies_or_results_change():
    identity = evaluation.evaluation_identity
    args = ({'catalog':'a'}, {'cases':[]}, {'core':'b'}, {'torch':'1'}, 'model:1', [])
    assert identity(*args) == identity(*args)
    assert identity(*args) != identity(*args[:3], {'torch':'2'}, *args[4:])
    assert identity(*args) != identity(*args[:4], 'model:2', args[5])
    assert identity(*args) != identity(*args[:5], [{'different':'ranking'}])


def test_real_search_is_reused_and_human_hypotheses_are_not_scored(tmp_path):
    catalog = evaluation.Catalog(evaluation.CachedEmbedder(Embedder()),tmp_path)
    catalog.replace([{'catalog_id':'a','codigo_servico':'01','descricao_item':'PINTOR HE 50%'},
                     {'catalog_id':'b','codigo_servico':'02','descricao_item':'PINTOR HE 50%'}])
    before = (tmp_path/'catalog.json').read_bytes()
    dataset = {'cases':[
        {'id':'control','kind':'control','query':'01','expected_codes':['01']},
        {'id':'human','kind':'human','query':'pintor hora extra 50%','candidate_codes':['01']}]}
    cases, metrics = evaluation.evaluate(catalog, dataset)
    assert all(metrics[label]['count'] == 1 and metrics[label]['hit@1'] == 1 for label in evaluation.WEIGHTS)
    assert all(run['target_rank'] is None for run in cases[1]['runs'].values())
    assert cases[1]['parts']['qualifiers'] == ['HE 50%']
    assert cases[1]['runs']['90/10']['effective_weights'] == {'activity':.9,'qualifiers':.1}
    assert evaluation.audit_vectors(catalog)['minimum_cosine'] == pytest.approx(1,abs=1e-5)
    assert (tmp_path/'catalog.json').read_bytes() == before
    assert json.loads(before)['items'][0]['descricao_item'] == 'PINTOR HE 50%'
