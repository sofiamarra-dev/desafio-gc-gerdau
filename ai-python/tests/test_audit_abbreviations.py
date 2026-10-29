import importlib

module = importlib.import_module('ai-python.audit_abbreviations')


def test_review_records_protected_service_name_without_changing_source():
    item = {'descricao_item': 'Aux. de Sv. Gerais', 'codigo_servico': '001'}
    report = module.audit([item])
    assert report['flagged_for_review'] == 0
    assert report['with_protected_expressions'] == 1
    assert report['rows'][0]['protected_expressions'] == ['Sv. Gerais']
    assert report['rows'][0]['activity'] == 'Aux. de Sv. Gerais'
    assert item == {'descricao_item': 'Aux. de Sv. Gerais', 'codigo_servico': '001'}


def test_context_sensitive_terms_are_preserved_and_flagged():
    report = module.audit([{'descricao_item': 'SV CONDULETE SEM ROSCA'},
                           {'descricao_item': 'SV ALVENARIA ESP 20CM'}])
    assert report['flagged_for_review'] == 2
    assert report['rows'][0]['activity'] == 'CONDULETE SEM ROSCA'
    assert report['rows'][1]['activity'] == 'ALVENARIA ESP 20CM'


def test_counts_are_per_record_and_alerts_may_overlap():
    report = module.audit([{'descricao_item': 'SV SV PINTOR HE 50% SEM'},
                           {'descricao_item': 'pintor'}])
    assert report['total'] == 2
    assert report['with_matches'] == 1
    assert report['qualifier_counts']['SV'] == 1
    assert report['flagged_for_review'] == 1
    assert len(report['rows'][0]['review_reasons']) == 1
    assert report['rows'][0]['qualifier_texts'] == ['SV', 'HE 50%']
    assert report['with_percentages'] == 1


def test_html_escapes_catalog_content():
    report = module.audit([{'descricao_item': 'SV PINTOR <script>alert(1)</script>'}])
    html = module.render_report(report)
    assert '<script>' not in html
    assert '&lt;script&gt;' in html
