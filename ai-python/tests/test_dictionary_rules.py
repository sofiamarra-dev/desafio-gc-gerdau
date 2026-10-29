import importlib
import pytest

rules=importlib.import_module('ai-python.abbreviations')

@pytest.mark.parametrize('alias',['ESP','ADM','A.D.M.','ÉSP','-ADM'])
def test_ambiguous_isolated_aliases_rejected(alias):
    with pytest.raises(ValueError):
        rules.prepare_dictionary([{'code':'TEST','meaning':'qualificador confirmado','variants':[alias]}],'test:1')

def test_current_dictionary_keeps_context_and_activity_untouched():
    for text in ['AUX ADM- HORA NORMAL À DISPOSIÇÃO','SV ALVENARIA ESP 20CM','Eletricista H ESP','Servente de obras -ADM']:
        parts=rules.INITIAL_DICTIONARY.split(text)
        assert 'ADM' in parts.activity or 'ESP' in parts.activity
    dictionary,_,_=rules.prepare_dictionary([{'code':'TEST','meaning':'qualificador confirmado','variants':['expressão completa confirmada']}],'test:1')
    assert dictionary.split('PINTOR expressão completa confirmada').qualifier_texts==('TEST',)
