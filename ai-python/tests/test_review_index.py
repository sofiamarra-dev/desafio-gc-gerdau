import hashlib
import importlib
import numpy as np
import pytest

ReviewIndex = importlib.import_module('ai-python.review_index').ReviewIndex


class Encoder:
    dimension = 8
    name = 'review-controlled'
    def __init__(self): self.calls = 0
    def embed(self, texts):
        self.calls += 1
        return np.asarray([np.random.default_rng(int(hashlib.sha256(text.encode()).hexdigest()[:8], 16)).normal(size=self.dimension) for text in texts], dtype=np.float32)


def build(tmp_path, rows, encoder=None):
    service = ReviewIndex(encoder or Encoder(), tmp_path)
    key = service.start(rows, [], 'test:1')['id']
    service.worker.submit(lambda: None).result(timeout=20)
    assert service.status(key)['status'] == 'READY', service.status(key)
    return service, key


def row(i, description): return {'catalog_id': str(i), 'descricao_item': description}


def test_blocked_result_matches_independent_full_matrix_for_more_than_one_block(tmp_path):
    rows = [row(i, f'ATIVIDADE {i}') for i in range(75)]
    service, key = build(tmp_path, rows)
    try:
        vectors = Encoder().embed([r['descricao_item'] for r in rows]).astype(np.float64)
        vectors /= np.linalg.norm(vectors, axis=1, keepdims=True)
        for i in (0, 32, 64, 74):
            expected = sorted((j for j in range(75) if j != i), key=lambda j: -float(vectors[i] @ vectors[j]))[:20]
            actual = service.neighbors(key, str(i))
            assert [r['catalog_id'] for r in actual] == [str(j) for j in expected]
            assert all(r['similaridade_semantica'] == pytest.approx(vectors[i] @ vectors[int(r['catalog_id'])], abs=1e-6) for r in actual)
    finally: service.worker.shutdown()


def test_contains_has_priority_even_with_lower_semantic_score_and_accents_are_ignored(tmp_path):
    class Collision(Encoder):
        def embed(self, texts):
            return np.asarray([[0, 1, 0, 0, 0, 0, 0, 0] if 'ESPECIAL' in text else [1, 0, 0, 0, 0, 0, 0, 0] for text in texts], dtype=np.float32)
    service, key = build(tmp_path, [row('query','Píntor'),row('contains','PINTOR ESPECIAL'),row('semantic','PINTURA')], Collision())
    try:
        results = service.neighbors(key, 'query')
        assert results[0]['catalog_id'] == 'contains'
        assert results[0]['correspondencia_palavras']
        assert results[0]['similaridade_semantica'] < results[1]['similaridade_semantica']
        assert all(r['catalog_id'] != 'query' for r in results)
    finally: service.worker.shutdown()


def test_removed_neighbors_are_excluded_and_best_is_recomputed_past_cached_twenty(tmp_path):
    rows = [row(i,f'ATIVIDADE {i}') for i in range(30)]
    service, key = build(tmp_path, rows)
    try:
        first = service.neighbors(key,'0')
        removed = {r['catalog_id'] for r in first}
        active = [r['catalog_id'] for r in rows if r['catalog_id'] not in removed]
        state = service.status(key, active)
        best = next(r for r in state['rows'] if r['catalog_id'] == '0')['pair']
        assert best == service.neighbors(key,'0',active)[0]
        assert best['catalog_id'] not in removed
        assert service.status(key,['0'])['rows'][0]['pair'] is None
        assert service.neighbors(key,'0',['0']) == []
    finally: service.worker.shutdown()


def test_persistent_cache_recovers_without_encoding_and_new_revision_has_new_identity(tmp_path):
    rows=[row('a','PINTOR'),row('b','PINTURA')]
    service,key=build(tmp_path,rows);service.worker.shutdown()
    class NoEncoding(Encoder):
        def embed(self,texts): raise AssertionError('O cache deveria evitar nova inferência')
    recovered,newkey=build(tmp_path,rows,NoEncoding())
    try:
        assert newkey == key
        assert recovered.neighbors(key,'a')[0]['catalog_id'] == 'b'
    finally: recovered.worker.shutdown()


def test_invalid_vectors_and_duplicate_ids_never_produce_ready_index(tmp_path):
    service=ReviewIndex(Encoder(),tmp_path)
    try:
        key=service.start([row('a','PINTOR'),row('a','MOTOR')],[],'v')['id']
        service.worker.submit(lambda:None).result(timeout=10)
        assert service.status(key)['status']=='ERROR'
    finally: service.worker.shutdown()


def test_empty_catalog_and_singleton_have_no_fake_pair(tmp_path):
    for rows in ([],[row('only','MOTOR')]):
        service,key=build(tmp_path,rows)
        try:
            assert all(r['pair'] is None for r in service.status(key)['rows'])
        finally: service.worker.shutdown()


def test_status_never_uses_self_as_pair_even_when_embeddings_are_identical(tmp_path):
    class SameVector(Encoder):
        def embed(self, texts): return np.tile(np.array([1,0,0,0,0,0,0,0],dtype=np.float32),(len(texts),1))
    service,key=build(tmp_path,[row('a','PINTOR'),row('b','MOTOR'),row('c','SOLDADOR')],SameVector())
    try:
        for record in service.status(key)['rows']:
            assert record['pair']['catalog_id'] != record['catalog_id']
            assert not record['pair']['correspondencia_palavras']
        index=service.jobs[key]['index'];index.pop('last_ranking',None)
        index['best'][0].insert(0,{'catalog_id':'a','similaridade_semantica':1.,'correspondencia_palavras':True})
        assert service.status(key)['rows'][0]['pair']['catalog_id'] != 'a'
    finally: service.worker.shutdown()


def test_persisted_self_reference_is_rebuilt_instead_of_displayed(tmp_path):
    import json
    rows=[row('a','PINTOR'),row('b','MOTOR')]
    service,key=build(tmp_path,rows);service.worker.shutdown()
    file=service.directory/(key+'.json')
    cached=json.loads(file.read_text());cached['0'][0]={'catalog_id':'a','similaridade_semantica':1.,'correspondencia_palavras':True}
    file.write_text(json.dumps(cached))
    recovered,newkey=build(tmp_path,rows)
    try:
        assert newkey==key
        assert recovered.status(key)['rows'][0]['pair']['catalog_id']=='b'
        assert not recovered.status(key)['rows'][0]['pair']['correspondencia_palavras']
        assert json.loads(file.read_text())['0'][0]['catalog_id']=='b'
    finally: recovered.worker.shutdown()


def test_http_job_and_neighbor_contract(tmp_path):
    from fastapi.testclient import TestClient
    api = importlib.import_module('ai-python.main')
    with TestClient(api.create_app(Encoder, tmp_path)) as client:
        response = client.post('/review-index', json={'items':[row('a','PINTOR'),row('b','PINTOR INDUSTRIAL')], 'dictionary':{'version':'test:1','entries':[]}})
        assert response.status_code == 200
        key = response.json()['id']
        client.app.state.review_index.worker.submit(lambda:None).result(timeout=10)
        status = client.post('/review-index/'+key, json={'active_ids':['a','b']})
        assert status.status_code == 200 and status.json()['status'] == 'READY'
        neighbors = client.post('/review-index/'+key, json={'selected':'a','active_ids':['a','b']})
        assert neighbors.json()['results'][0]['catalog_id'] == 'b'
        assert neighbors.json()['results'][0]['correspondencia_palavras']
        assert client.post('/review-index/missing',json={}).status_code == 404
