"""Avaliação offline da busca real; lê uma cópia e nunca reconstrói o índice original."""
import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import sys
from datetime import datetime, timezone

import numpy as np
from .core import Catalog
from .embedder import MiniLMEmbedder, normalized, unit_vector

WEIGHTS = {'80/20': (.8, .2), '90/10': (.9, .1), '95/5': (.95, .05)}
FILES = ('catalog.json', 'dictionary.json', 'vectors.npz')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def evaluation_identity(hashes, dataset, engine_hashes, versions, model_revision, cases):
    content = [hashes, dataset, engine_hashes, versions, model_revision, cases]
    return hashlib.sha256(json.dumps(content, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def snapshot(source, destination):
    destination.mkdir(parents=True, exist_ok=True)
    hashes = {name: digest(source / name) for name in FILES}
    for name in FILES:
        target = destination / name
        if target.exists() and digest(target) != hashes[name]:
            raise ValueError('A pasta de saída contém outra base. Escolha uma pasta nova.')
        if not target.exists():
            shutil.copy2(source / name, target)
    if hashes != {name: digest(source / name) for name in FILES} or hashes != {name: digest(destination / name) for name in FILES}:
        raise ValueError('A base mudou durante a cópia. Execute novamente em uma pasta nova.')
    return hashes


class CachedEmbedder:
    def __init__(self, model):
        self.model, self.name, self.dimension = model, model.name, model.dimension
        self.cache = {}

    def embed(self, texts):
        missing = list(dict.fromkeys(t for t in texts if t and t not in self.cache))
        if missing:
            self.cache.update(zip(missing, self.model.embed(missing)))
        zero = np.zeros(self.dimension, dtype=np.float32)
        return np.stack([self.cache[t] if t else zero for t in texts]) if texts else np.zeros((0, self.dimension), dtype=np.float32)


def technical_metrics(runs):
    """Hit não é recall: basta um alvo conhecido; nenhum julgamento é inferido."""
    ranks = [run['target_rank'] for run in runs]
    n = len(ranks)
    return {'count': n, **{f'hit@{k}': sum(rank is not None and rank <= k for rank in ranks) / n if n else None for k in (1, 5, 20)},
            'mrr@20': sum(1 / rank for rank in ranks if rank is not None) / n if n else None}


def is_target(row, case):
    return row['codigo_servico'] in case.get('expected_codes', []) or (
        bool(case.get('expected_description')) and normalized(row['descricao_item']) == normalized(case['expected_description']))


def summarize(row):
    return {key: row.get(key) for key in ('catalog_id', 'codigo_servico', 'codigo_fornecedor', 'descricao_item',
        'atividade', 'qualificadores', 'similaridade_semantica', 'similaridade_atividade', 'similaridade_qualificadores',
        'correspondencia_exata', 'correspondencia_palavras')}


def semantic_order(catalog, query):
    item = {'descricao_item': query}
    activity, qualifiers = catalog._description_text(item), catalog._context_text(item)
    vectors = unit_vector(catalog.embedder.embed([activity, qualifiers]))
    a, q = catalog.description_vectors @ vectors[0], catalog.context_vectors @ vectors[1]
    wa = catalog.description_weight if activity else 0
    wq = catalog.context_weight if qualifiers else 0
    total = wa + wq
    wa, wq = (wa / total, wq / total) if total else (0, 0)
    scores = wa * a + wq * q
    order = np.argsort(-scores, kind='stable')[:20]
    rows = [dict(catalog.items[int(i)], similaridade_semantica=round(float(np.clip(scores[i], -1, 1)), 4),
                 similaridade_atividade=round(float(np.clip(a[i], -1, 1)), 4),
                 similaridade_qualificadores=round(float(np.clip(q[i], -1, 1)), 4)) for i in order]
    return rows, {'activity': wa, 'qualifiers': wq}, scores


def audit_vectors(catalog):
    indices = sorted(set(np.linspace(0, len(catalog.items) - 1, min(24, len(catalog.items)), dtype=int))) if catalog.items else []
    checks = []
    for field, matrix in (('activity', catalog.description_vectors), ('qualifiers', catalog.context_vectors)):
        texts = [(catalog._description_text if field == 'activity' else catalog._context_text)(catalog.items[i]) for i in indices]
        fresh = unit_vector(catalog.embedder.embed(texts))
        for i, text, vector in zip(indices, texts, fresh):
            score = float(matrix[i] @ vector) if text else (1.0 if not np.any(matrix[i]) else 0.0)
            checks.append(score)
    minimum = min(checks, default=1)
    if minimum < .995:
        raise ValueError(f'Os vetores salvos divergem do modelo atual (cosseno mínimo {minimum:.5f}). Não atribua resultados à busca atual sem reconstruir uma cópia do índice.')
    return {'sample_rows': len(indices), 'comparisons': len(checks), 'minimum_cosine': minimum, 'threshold': .995}


def evaluate(catalog, dataset):
    by_id = {item['catalog_id']: i for i, item in enumerate(catalog.items)}
    cases = []
    for case in dataset['cases']:
        parts = catalog.dictionary.split(case['query'])
        runs = {}
        for label, (wa, wq) in WEIGHTS.items():
            catalog.description_weight, catalog.context_weight = wa, wq
            ranked = catalog.search(case['query'])
            semantic, weights, scores = semantic_order(catalog, case['query'])
            # Detecta divergência entre o diagnóstico e a implementação real.
            for row in ranked:
                assert abs(row['similaridade_semantica'] - float(np.clip(scores[by_id[row['catalog_id']]], -1, 1))) < .00011
            runs[label] = {'results': [summarize(row) for row in ranked], 'semantic_results': [summarize(row) for row in semantic],
                           'effective_weights': weights,
                           'target_rank': next((i + 1 for i, row in enumerate(ranked) if is_target(row, case)), None) if case['kind'] == 'control' else None}
        cases.append(dict(case, parts={'activity':parts.activity, 'qualifiers':list(parts.qualifier_texts),
                                      'standardized':parts.standardized, 'needs_review':parts.needs_review}, runs=runs))
        print(f"{case['id']}: concluído", flush=True)
    controls = {label:technical_metrics([case['runs'][label] for case in cases if case['kind'] == 'control']) for label in WEIGHTS}
    return cases, controls


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data-dir', type=Path, required=True)
    parser.add_argument('--cases', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    os.environ['HF_HUB_OFFLINE'] = '1'
    os.environ['TRANSFORMERS_OFFLINE'] = '1'
    output = args.output.resolve()
    if output == args.data_dir.resolve() or args.data_dir.resolve() in output.parents:
        raise ValueError('Use uma saída fora do diretório do índice original.')
    output.mkdir(parents=True, exist_ok=True)
    hashes = snapshot(args.data_dir, output / 'snapshot')
    dataset = json.loads(args.cases.read_text(encoding='utf-8'))
    if len({case['id'] for case in dataset['cases']}) != len(dataset['cases']):
        raise ValueError('IDs de casos duplicados.')
    real_model = MiniLMEmbedder()
    catalog = Catalog(CachedEmbedder(real_model), output / 'snapshot')
    if catalog.requires_reindex:
        raise ValueError('Snapshot incompatível. Não foi feita reconstrução nem alteração do índice original.')
    audit = audit_vectors(catalog)
    cases, controls = evaluate(catalog, dataset)
    versions = {name:importlib.metadata.version(name) for name in ('sentence-transformers','transformers','torch','numpy')}
    engine_hashes = {name:digest(Path(__file__).with_name(name)) for name in ('core.py','abbreviations.py','embedder.py','evaluate_search.py','evaluation_report.html')}
    model_revision = getattr(real_model.model[0].auto_model.config, '_commit_hash', None)
    eval_id = evaluation_identity(hashes, dataset, engine_hashes, versions, model_revision, cases)
    descriptions_by_code = {}
    for row in catalog.items:
        descriptions_by_code.setdefault(row['codigo_servico'], set()).add(normalized(row['descricao_item']))
    result = {'schema_version':1, 'evaluation_id':eval_id, 'created_at':datetime.now(timezone.utc).isoformat(),
              'model':catalog.embedder.name,'model_revision':model_revision,
              'dimension':catalog.embedder.dimension,'records':len(catalog.items),
              'distinct_codes':len({r['codigo_servico'] for r in catalog.items}),
              'dictionary_version':catalog.dictionary.version, 'source_revision':catalog.revision,
              'source_hashes':hashes,'engine_hashes':engine_hashes,'versions':versions,'vector_audit':audit,
              'technical_metrics':controls,'weights':list(WEIGHTS),'cases':cases,
              'known_codes':sorted(descriptions_by_code),
              'ambiguous_codes':sorted(code for code, descriptions in descriptions_by_code.items() if len(descriptions) > 1)}
    if hashes != {name:digest(args.data_dir / name) for name in FILES}:
        raise ValueError('A base de origem mudou durante a execução. Preserve os resultados como snapshot, não como estado mais recente.')
    (output/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    template = Path(__file__).with_name('evaluation_report.html').read_text(encoding='utf-8')
    embedded = json.dumps(result,ensure_ascii=False).replace('<','\\u003c').replace('\u2028','\\u2028').replace('\u2029','\\u2029')
    (output/'review.html').write_text(template.replace('__EVALUATION_DATA__', embedded),encoding='utf-8')
    print(json.dumps({'output':str(output),'technical_metrics':controls,'vector_audit':audit},ensure_ascii=False),flush=True)


if __name__ == '__main__':
    if hasattr(sys.stdout, 'reconfigure'): sys.stdout.reconfigure(encoding='utf-8')
    main()
