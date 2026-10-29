import importlib
import json
import numpy as np
import pytest

rules=importlib.import_module('ai-python.abbreviations')
core=importlib.import_module('ai-python.core')

@pytest.mark.parametrize('alias',['H. EXTRA','H.EXTRA','H . EXTRA','h. extras','H.EXTRAS'])
def test_dotted_he_and_negation(alias):
    parts=rules.INITIAL_DICTIONARY.split(f'PINTOR {alias} 50%')
    assert parts.activity=='PINTOR'
    assert parts.qualifier_texts==('HE 50%',)
    negative=rules.INITIAL_DICTIONARY.split(f'PINTOR SEM {alias} 50%')
    assert negative.qualifier_texts==()
    assert negative.activity==negative.original

@pytest.mark.parametrize('alias',['hora extra','horas extras','H.EXTRA','H. EXTRAS'])
def test_explicit_he_after_other_additional_is_grouped_and_idempotent(alias):
    parts=rules.INITIAL_DICTIONARY.split(f'ASG com insalubridade {alias} 50%')
    assert parts.activity=='ASG com'
    assert parts.qualifier_texts==('INSA','HE 50%')
    assert parts.standardized=='ASG com INSA (HE 50%)'
    again=rules.INITIAL_DICTIONARY.split(parts.standardized)
    assert again.activity==parts.activity
    assert again.qualifier_texts==parts.qualifier_texts
    assert again.standardized==parts.standardized
    for match in parts.matches: assert parts.original[match.start:match.end]==match.original

def test_ambiguous_shorthand_does_not_guess_rate_owner():
    parts=rules.INITIAL_DICTIONARY.split('PINTOR HE NOT 50%')
    assert parts.activity=='PINTOR 50%'
    assert parts.qualifier_texts==('HE','NOT')
    assert rules.INITIAL_DICTIONARY.split('SV SOLUÇÃO 50% TUBO 20MM').activity=='SOLUÇÃO 50% TUBO 20MM'

class Embedder:
    dimension=2
    name='splitter-reindex-test'
    def __init__(self):self.calls=[]
    def embed(self,texts):
        self.calls.extend(texts)
        return np.array([[len(t),1] for t in texts],dtype=np.float32)

def test_splitter_upgrade_requires_rebuild_even_with_same_sql_dictionary(tmp_path):
    catalog=core.Catalog(Embedder(),tmp_path)
    entries=[{'code':'HE','meaning':'hora extra','variants':['H. EXTRA'],'active':True}]
    catalog.update_dictionary(entries,'sql:1')
    rows=[{'codigo_servico':'1','descricao_item':'PINTOR H.EXTRA 50%'}]
    catalog.replace(rows,revision='db:1')
    path=tmp_path/'catalog.json'
    contents=json.loads(path.read_text(encoding='utf-8'));contents.pop('splitter_version')
    path.write_text(json.dumps(contents),encoding='utf-8')
    restored=core.Catalog(Embedder(),tmp_path)
    assert restored.requires_reindex and restored.revision=='db:1'
    with pytest.raises(ValueError):restored.search('pintor')
    restored.replace(rows,revision='db:1')
    assert not core.Catalog(Embedder(),tmp_path).requires_reindex
    assert restored.items[0]['qualificadores']==['HE 50%']
    assert restored.items[0]['descricao_item']=='PINTOR H.EXTRA 50%'

@pytest.mark.parametrize('space',['\u00a0','\u202f'])
def test_nonbreaking_space_between_qualifier_and_percentage(space):
    parts=rules.INITIAL_DICTIONARY.split(f'PINTOR H.EXTRA{space}50{space}%')
    assert parts.activity=='PINTOR'
    assert parts.qualifier_texts==('HE 50%',)
