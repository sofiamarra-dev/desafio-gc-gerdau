import importlib
import numpy as np
import pytest

ranking=importlib.import_module('ai-python.activity_ranking')
core=importlib.import_module('ai-python.core')

def test_whole_words_accents_empty_and_unknown_queries():
    index=ranking.ActivityRanking(['MAÇÃ','MACACO','MOTORISTA','Motor gasolina'])
    assert index.scores('maça')[0]>0 and index.scores('maça')[1]==0
    assert index.scores('motor')[2]==0 and index.scores('motor')[3]>0
    assert not index.scores('de para e 50').any()
    assert not index.scores('binoclo').any()
    assert ranking.ActivityRanking([]).scores('motor').size==0

def test_duplicate_suppliers_do_not_distort_term_statistics():
    once=ranking.ActivityRanking(['coifa industrial','limpeza predial'])
    duplicate=ranking.ActivityRanking(['coifa industrial','limpeza predial','coifa industrial'])
    assert duplicate.scores('coifa limpeza')[:2]==pytest.approx(once.scores('coifa limpeza'))
    assert duplicate.scores('coifa')[0]==duplicate.scores('coifa')[2]

class Embedder:
    name='activity-ranking-test'
    dimension=2
    def __init__(self):self.calls=[]
    def embed(self,texts):
        self.calls.extend(texts)
        return np.array([[.8,.6] if text=='Higienização de coifa' else [1,0] for text in texts],dtype=np.float32)

def test_partial_activity_can_promote_a_known_match_without_falsifying_semantic_score(tmp_path):
    model=Embedder();catalog=core.Catalog(model,tmp_path)
    rows=[{'catalog_id':'a','codigo_servico':'01','descricao_item':'Higienização de coifa'},
          {'catalog_id':'b','codigo_servico':'02','descricao_item':'Auxiliar de refeitório'}]
    catalog.replace(rows)
    catalog.activity_text_bonus=0
    assert catalog.search('limpeza de coifa')[0]['catalog_id']=='b'
    catalog.activity_text_bonus=.25;model.calls.clear()
    result=catalog.search('limpeza de coifa')[0]
    assert result['catalog_id']=='a'
    assert result['similaridade']==result['similaridade_semantica']==pytest.approx(.8)
    assert result['pontuacao_ordenacao']==pytest.approx(1.05)
    assert result['termos_atividade_encontrados']==['coifa']
    assert model.calls==['limpeza de coifa']
    assert core.Catalog(Embedder(),tmp_path).search('limpeza de coifa')[0]['catalog_id']=='a'
    assert catalog.search('02')[0]['catalog_id']=='b'
    catalog.replace([rows[1]])
    assert catalog.search('limpeza de coifa')[0]['pontuacao_textual_atividade']==0

def test_dictionary_change_invalidates_text_index_and_waits_for_sync(tmp_path):
    catalog=core.Catalog(Embedder(),tmp_path)
    rows=[{'codigo_servico':'1','descricao_item':'PINTOR HX 50%'}]
    catalog.replace(rows)
    catalog.update_dictionary([{'code':'HE','meaning':'hora extra','variants':['HX']}],'sql:2')
    assert catalog.activity_ranking is None
    with pytest.raises(ValueError):catalog.search('pintor')
    catalog.replace(rows)
    result=catalog.search('HE 50%')[0]
    assert result['pontuacao_textual_atividade']==0
    assert result['pesos_aplicados']=={'atividade':0.,'qualificadores':1.}

@pytest.mark.parametrize('bonus',[-1,2,float('nan')])
def test_rejects_invalid_strength(tmp_path,bonus):
    with pytest.raises(ValueError):core.Catalog(Embedder(),tmp_path,activity_text_bonus=bonus)
