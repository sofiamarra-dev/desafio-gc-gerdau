import importlib
import unicodedata

import pytest

module = importlib.import_module('ai-python.abbreviations')
Abbreviation = module.Abbreviation
Dictionary = module.AbbreviationDictionary
dictionary = module.INITIAL_DICTIONARY


def test_separates_activity_and_keeps_original_and_positions():
    original = 'SV PINTOR HORA EXTRA'
    result = dictionary.split(original)
    assert result.original == original
    assert result.activity == 'PINTOR'
    assert result.qualifiers == ('SV', 'HE')
    assert result.standardized == 'SV PINTOR HE'
    assert result.dictionary_version == 'search-8'
    assert not result.needs_review
    for match in result.matches:
        assert original[match.start:match.end] == match.original


@pytest.mark.parametrize('description', [
    'Serviço pintor horas extras', 'SERVICO pintor HORAS   EXTRAS',
    unicodedata.normalize('NFD', 'Serviço pintor horas extras'),
])
def test_accents_case_spaces_and_unicode(description):
    result = dictionary.split(description)
    assert result.activity == 'pintor'
    assert result.qualifiers == ('SV', 'HE')


def test_longest_phrase_wins():
    custom = Dictionary((Abbreviation('H', 'hora'),
                         Abbreviation('HE', 'hora extra')), 'test')
    assert custom.split('pintor hora extra').qualifiers == ('HE',)


def test_no_substring_or_phrase_across_punctuation():
    result = dictionary.split('HELICÓPTERO observação hora, extra FDS ESP SEM')
    assert result.activity == result.original
    assert result.qualifiers == ()


def test_preserves_unknown_words_and_numbers():
    result = dictionary.split('SV manutenção motor 220V PERI 30%')
    assert result.activity == 'manutenção motor 220V'
    assert result.qualifier_texts == ('SV', 'PERI 30%')
    assert result.qualifiers == ('SV', 'PERI')


@pytest.mark.parametrize('description', ['', '   ', 'SV HE', 'SV / HE'])
def test_missing_activity_requires_review(description):
    assert dictionary.split(description).needs_review


def test_repeated_qualifiers_and_idempotent_standardization():
    result = dictionary.split('serviço pintor SV hora extra HE')
    assert result.qualifiers == ('SV', 'HE')
    assert dictionary.split(result.standardized).standardized == result.standardized


@pytest.mark.parametrize('second', [
    Abbreviation('SV', 'suco verde'),
    Abbreviation('OUTRA', 'outro', ('  SERVICO  ',)),
    Abbreviation('OUTRA', 'outro', ('sv',)),
])
def test_dictionary_rejects_duplicate_codes_and_aliases(second):
    with pytest.raises(ValueError):
        Dictionary((Abbreviation('SV', 'serviço'), second), 'test')


def test_empty_dictionary_preserves_text():
    result = Dictionary((), 'empty').split('pintura de parede')
    assert result.activity == result.original == result.standardized


@pytest.mark.parametrize(('original', 'activity'), [
    ('SV-SOLDADOR', 'SOLDADOR'),
    ('SV - PINTOR - HE', 'PINTOR'),
    ('SV—PINTOR–HE', 'PINTOR'),
    ('PINTOR - HE - INDUSTRIAL', 'PINTOR INDUSTRIAL'),
    ('PINTOR (HORA EXTRA)', 'PINTOR'),
    ('PINTOR - [HE]', 'PINTOR'),
    ('PINTOR ([HE])', 'PINTOR'),
    ('PINTOR (HE / HNOT)', 'PINTOR'),
    ('SV TÉCNICO-ADMINISTRATIVO NR-35 220-380V', 'TÉCNICO-ADMINISTRATIVO NR-35 220-380V'),
    ('SV CÂMARA -10°C', 'CÂMARA -10°C'),
    ('SV -10°C', '-10°C'),
    ('SV TUBO 1/2 20MM', 'TUBO 1/2 20MM'),
    ('PINTOR - INDUSTRIAL', 'PINTOR - INDUSTRIAL'),
    ('SV PINTOR - INDUSTRIAL', 'PINTOR - INDUSTRIAL'),
    ('SV PINTOR (INDUSTRIAL)', 'PINTOR (INDUSTRIAL)'),
    ('PINTOR ()', 'PINTOR ()'),
])
def test_cleans_only_punctuation_affected_by_extraction(original, activity):
    result = dictionary.split(original)
    assert result.activity == activity
    assert result.original == original


@pytest.mark.parametrize('phrase', ['serviços gerais', 'SERVICOS GERAIS', 'Sv. Gerais', 'sv gerais'])
def test_service_name_is_protected_before_extraction(phrase):
    original = f'SV AUXILIAR DE {phrase} - HE'
    result = dictionary.split(original)
    assert result.activity == f'AUXILIAR DE {phrase}'
    assert result.qualifiers == ('SV', 'HE')
    assert result.protected_expressions == (phrase,)
    assert phrase in result.standardized


def test_protection_uses_whole_tokens_and_does_not_cross_punctuation():
    assert dictionary.split('serviços generalizados').qualifiers == ('SV',)
    assert dictionary.split('serviços, gerais').protected_expressions == ()


def test_no_alias_can_cross_a_protected_phrase():
    custom = Dictionary((Abbreviation('X', 'auxiliar de serviços gerais'),), 'test',
                        protected_phrases=('serviços gerais',))
    assert custom.split('auxiliar de serviços gerais').qualifiers == ()


def test_extra_and_night_are_independent_qualifiers():
    result = dictionary.split('AJUDANTE (HORA EXTRA NOTURNA DOM.FER)')
    assert result.qualifiers == ('HE', 'NOT', 'DOMF')
    assert result.activity == 'AJUDANTE'
    assert result.standardized == 'AJUDANTE (HE NOT DOMF)'


@pytest.mark.parametrize('variant', ['HNOT', 'NOT', 'noturno', 'noturna',
                                   'noturnos', 'noturnas', 'hora noturna', 'horas noturnas'])
def test_night_variants_share_canonical_code(variant):
    result = dictionary.split(f'PINTOR ({variant})')
    assert result.activity == 'PINTOR'
    assert result.qualifiers == ('NOT',)
    assert result.standardized == 'PINTOR (NOT)'


def test_legacy_and_new_night_code_deduplicate_without_substring_matches():
    result = dictionary.split('PINTOR HE HNOT NOT noturna NOTIFICACAO')
    assert result.qualifiers == ('HE', 'NOT')
    assert result.activity == 'PINTOR NOTIFICACAO'


@pytest.mark.parametrize('variant', [
    'DOMF', 'DOM.FER', 'DOM/FER', 'DOM FER', 'DOM E FER', 'dom . fer',
    'domingo / feriado', 'domingo e feriado', 'domingos e feriados',
    'domingos / feriados', 'DOM E FERIADOS', 'DOM E FERIADO', 'DOM/FERIADO',
    'DOM FERIADO', 'DOMINGO/FER',
])
def test_sunday_holiday_variants(variant):
    result = dictionary.split(f'AJUDANTE (HE NOT {variant})')
    assert result.activity == 'AJUDANTE'
    assert result.qualifiers == ('HE', 'NOT', 'DOMF')
    assert result.standardized == 'AJUDANTE (HE NOT DOMF)'


@pytest.mark.parametrize('description', [
    'SERVIDOR DOMINIO FERRAMENTAS', 'DOM A DOM', 'DOM/SEG',
    'DOM', 'FER', 'DOM EFERIADO', 'DOM-FERI EVENTUA',
])
def test_ambiguous_or_unknown_day_expressions_remain_in_activity(description):
    result = dictionary.split(description)
    assert result.activity == description
    assert result.qualifiers == ()


def test_sunday_holiday_qualifier_keeps_percentage_and_deduplicates():
    result = dictionary.split('AJUDANTE HE DOM/FER 100% DOMF')
    assert result.activity == 'AJUDANTE 100%'
    assert result.qualifiers == ('HE', 'DOMF')
    assert dictionary.split(result.standardized).standardized == result.standardized


@pytest.mark.parametrize(('expression', 'label'), [
    ('HE 50%', 'HE 50%'), ('hora extra 100 %', 'HE 100%'),
    ('PERI 30%', 'PERI 30%'), ('INSA 20%', 'INSA 20%'),
    ('NOT 20%', 'NOT 20%'), ('HE 37.5%', 'HE 37,5%'),
    ('HE 37,5%', 'HE 37,5%'), ('HE 150%', 'HE 150%'),
])
def test_percentage_stays_attached_to_qualifier(expression, label):
    original = f'PINTOR ({expression}) 220-380V'
    result = dictionary.split(original)
    assert result.activity == 'PINTOR 220-380V'
    assert result.qualifier_texts == (label,)
    assert result.standardized == f'PINTOR ({label}) 220-380V'
    assert result.original == original
    for match in result.matches:
        assert original[match.start:match.end] == match.original
    assert dictionary.split(result.standardized).qualifier_texts == (label,)


@pytest.mark.parametrize('expression', [
    'HE NOT 50%', 'HE DOMF 100%', 'HE / 50%', 'HE -50%',
    'HE 50-100%', 'HE 50%%', 'HE 50%ABC', 'HE\n50%', 'HE 50',
])
def test_unclear_or_invalid_percentages_are_not_assigned(expression):
    result = dictionary.split(f'PINTOR {expression}')
    assert all(match.percentage is None for match in result.matches)
    assert '50' in result.activity or '100' in result.activity


def test_percentage_without_qualifier_and_technical_measurements_remain():
    original = 'SV SOLUÇÃO 50% TUBO 20MM'
    result = dictionary.split(original)
    assert result.activity == 'SOLUÇÃO 50% TUBO 20MM'
    assert result.qualifier_texts == ('SV',)


def test_several_explicit_additional_rates_keep_their_own_values():
    result = dictionary.split('PINTOR HE 50% NOT 20% HE 100%')
    assert result.activity == 'PINTOR'
    assert result.qualifier_texts == ('HE 50%', 'NOT 20%', 'HE 100%')
    assert result.qualifiers == ('HE', 'NOT')


@pytest.mark.parametrize(('original', 'activity'), [
    ('Sv. Conservação e Limpeza', 'Conservação e Limpeza'),
    ('PINTOR (NOT.)', 'PINTOR'),
    ('PINTOR - HE 50%.', 'PINTOR'),
    ('SV. TÉC. SEGURANÇA (HE.)', 'TÉC. SEGURANÇA'),
    ('SV PORTA 0.70 X 2.10', 'PORTA 0.70 X 2.10'),
    ('SV LUVA 1.1/4', 'LUVA 1.1/4'),
    ('SV NR.35 VERSÃO 1.2.3', 'NR.35 VERSÃO 1.2.3'),
    ('SV AUX. DE SV. GERAIS HE.', 'AUX. DE SV. GERAIS'),
    ('PINTOR HE .5MM', 'PINTOR .5MM'),
    ('PINTOR HE.5MM', 'PINTOR .5MM'),
    ('PINTOR HE...', 'PINTOR ...'),
    ('TÉC. SEGURANÇA', 'TÉC. SEGURANÇA'),
    ('SV LIMPEZA .', 'LIMPEZA .'),
    ('SV DESM.SV EXTRA', 'DESM. EXTRA'),
])
def test_dot_cleanup_preserves_abbreviations_values_and_identifiers(original, activity):
    result = dictionary.split(original)
    assert result.activity == activity
    assert result.original == original


def test_dotted_day_expression_and_decimal_percentage_still_work():
    result = dictionary.split('PINTOR HE 37.5% DOM.FER')
    assert result.activity == 'PINTOR'
    assert result.qualifier_texts == ('HE 37,5%', 'DOMF')
