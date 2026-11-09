import importlib
import pytest
from fastapi.testclient import TestClient
from test_bulk_preview import Embedder

api = importlib.import_module('ai-python.main')
module = importlib.import_module('ai-python.abbreviations')

ENTRIES = [
    {'code': 'SV', 'meaning': 'serviço', 'variants': ['sv-', 'sv.', 'serviços']},
    {'code': 'HE', 'meaning': 'hora extra', 'variants': ['horas extras']},
]

@pytest.mark.parametrize('text,expected', [
    ('sv-pintor', 'SV pintor'),
    ('sv-   pintor', 'SV pintor'),
    ('  serviço  pintor\t horas extras  ', 'SV pintor HE'),
    ('sv.pintor', 'SV pintor'),
    ('sv-pintor hora extra 50%', 'SV pintor HE 50%'),
    ('SV-UNIÃO SOLDÁVEL 25 MM', 'SV UNIÃO SOLDÁVEL 25 MM'),
    ('SV-tubo 25-30 mm NR.35', 'SV tubo 25-30 mm NR.35'),
])
def test_standardization_has_word_boundaries_and_is_idempotent(text, expected):
    dictionary, _, _ = module.prepare_dictionary(ENTRIES, 'dictionary:1')
    assert dictionary.split(text).standardized == expected
    assert dictionary.split(expected).standardized == expected
    assert '  ' not in expected

def test_batch_endpoint_uses_snapshot_preserves_order_and_does_not_change_catalog(tmp_path):
    with TestClient(api.create_app(Embedder, tmp_path)) as client:
        data = {'descriptions': ['sv-pintor', 'serviço   eletricista'],
                'dictionary': {'version': 'dictionary:1', 'entries': ENTRIES}}
        response = client.post('/dictionary/standardize', json=data)
        assert response.status_code == 200, response.text
        assert response.json() == {'dictionary_version': 'dictionary:1',
                                   'descriptions': ['SV pintor', 'SV eletricista']}
        assert client.get('/catalog').json()['items'] == []
        data['dictionary']['entries'][0]['active'] = False
        assert client.post('/dictionary/standardize', json=data).json()['descriptions'][0] == 'sv-pintor'

@pytest.mark.parametrize('text', ['', '   ', 'x' * 301])
def test_invalid_description_rejects_whole_batch(tmp_path, text):
    with TestClient(api.create_app(Embedder, tmp_path)) as client:
        response = client.post('/dictionary/standardize', json={
            'descriptions': ['sv-pintor', text],
            'dictionary': {'version': 'dictionary:1', 'entries': ENTRIES}})
        assert response.status_code == 422
