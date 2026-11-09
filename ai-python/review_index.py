"""Análise exata em blocos, índice invertido e cache de snapshots imutáveis."""
import hashlib
import json
import re
import threading
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
from .abbreviations import prepare_dictionary
from .embedder import normalized, unit_vector


class ReviewIndex:
    def __init__(self, embedder, directory):
        self.embedder = embedder
        self.directory = Path(directory) / 'review-index'
        self.jobs = {}
        self.lock = threading.RLock()
        self.text_vectors = OrderedDict()
        self.worker = ThreadPoolExecutor(max_workers=1, thread_name_prefix='review-index')

    def start(self, items, entries, version):
        payload = {'items': items, 'entries': entries, 'version': version,
                   'model': self.embedder.name, 'algorithm': 'blocked-exact-90-10-v2-no-self'}
        key = hashlib.sha256(json.dumps(payload, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        with self.lock:
            if key in self.jobs:
                if self.jobs[key]['status'] != 'ERROR':
                    return {'id': key, 'status': self.jobs[key]['status']}
                self.jobs.pop(key)
            if any(job['status'] == 'RUNNING' for job in self.jobs.values()):
                raise ValueError('Já existe uma análise em andamento. Aguarde sua conclusão.')
            # No máximo dois snapshots com vetores na memória.
            if len(self.jobs) >= 2:
                self.jobs.pop(next(iter(self.jobs)))
            self.jobs[key] = {'status': 'RUNNING', 'progress': 0}
            self.worker.submit(self._build, key, items, entries, version)
        return {'id': key, 'status': 'RUNNING'}

    def _build(self, key, items, entries, version):
        try:
            ids = [row['catalog_id'] for row in items]
            if len(set(ids)) != len(ids) or any(not value for value in ids):
                raise ValueError('Identificadores inválidos no catálogo.')
            dictionary, _, _ = prepare_dictionary(entries, version)
            parts = [dictionary.split(row['descricao_item']) for row in items]
            activities = ['' if part.needs_review else part.activity for part in parts]
            qualifiers = [' | '.join(part.qualifier_texts) for part in parts]
            texts = list(dict.fromkeys(text for text in activities + qualifiers if text))
            cache_file = self.directory / (key + '.npz')
            if cache_file.exists():
                with np.load(cache_file, allow_pickle=False) as saved:
                    activity = saved['activity'].copy(); context = saved['context'].copy()
                if activity.shape != (len(items), self.embedder.dimension) or context.shape != activity.shape:
                    raise ValueError('Cache de análise incompatível.')
            else:
                missing = [text for text in texts if text not in self.text_vectors]
                vectors = self.embedder.embed(missing) if missing else np.empty((0, self.embedder.dimension))
                if vectors.shape != (len(missing), self.embedder.dimension) or not np.isfinite(vectors).all():
                    raise ValueError('O modelo retornou vetores inválidos.')
                lookup = {text: self.text_vectors[text] for text in texts if text in self.text_vectors}
                lookup.update(zip(missing, vectors)); zero = np.zeros(self.embedder.dimension, dtype=np.float32)
                for text, vector in lookup.items(): self.text_vectors[text] = vector
                while len(self.text_vectors) > 100_000: self.text_vectors.popitem(last=False)
                activity = unit_vector(np.asarray([lookup.get(text, zero) for text in activities], dtype=np.float32).reshape(len(items), self.embedder.dimension))
                context = unit_vector(np.asarray([lookup.get(text, zero) for text in qualifiers], dtype=np.float32).reshape(len(items), self.embedder.dimension))
                self.directory.mkdir(parents=True, exist_ok=True)
                temporary = self.directory / (key + '.tmp')
                with temporary.open('wb') as file:
                    np.savez(file, activity=activity, context=context)
                temporary.replace(cache_file)
            words = [set(normalized(row['descricao_item']).split()) for row in items]
            postings = {}
            for i, tokens in enumerate(words):
                for token in tokens: postings.setdefault(token, set()).add(i)
            weights = np.array([[.9 if a else 0., .1 if q else 0.] for a, q in zip(activities, qualifiers)], dtype=np.float32).reshape(len(items), 2)
            weights /= np.maximum(weights.sum(axis=1, keepdims=True), 1e-12)
            index = {'items': items, 'ids': ids, 'lookup': dict(zip(ids, range(len(ids)))),
                     'activity': activity, 'context': context, 'weights': weights,
                     'words': words, 'postings': postings, 'best': {}}
            ranks_file = self.directory / (key + '.json')
            if ranks_file.exists():
                try:
                    saved = {int(i): rows for i, rows in json.loads(ranks_file.read_text()).items()}
                    if self._valid_cache(index, saved):
                        index['best'] = saved
                        with self.lock: self.jobs[key].update(status='READY', progress=len(items), index=index)
                        return
                except (ValueError, TypeError, AttributeError):
                    pass
            all_active = set(range(len(items)))
            for offset in range(0, len(items), 64):
                stop = min(offset + 64, len(items))
                scores = (activity[offset:stop] @ activity.T) * weights[offset:stop, 0, None]
                scores += (context[offset:stop] @ context.T) * weights[offset:stop, 1, None]
                for local, i in enumerate(range(offset, stop)):
                    index['best'][i] = self._top(index, i, scores[local], all_active)
                with self.lock: self.jobs[key]['progress'] = stop
            temporary = self.directory / (key + '.json.tmp')
            temporary.write_text(json.dumps(index['best']), encoding='utf-8')
            temporary.replace(ranks_file)
            with self.lock: self.jobs[key].update(status='READY', index=index)
        except Exception as error:
            with self.lock: self.jobs[key].update(status='ERROR', error=str(error))

    def _contains(self, index, i):
        tokens = index['words'][i]
        if not tokens: return set()
        ordered = sorted((index['postings'][word] for word in tokens), key=len)
        found = ordered[0].copy()
        for posting in ordered[1:]: found.intersection_update(posting)
        found.discard(i)
        return found

    def _valid_cache(self, index, saved):
        if set(saved) != set(range(len(index['ids']))): return False
        for i, rows in saved.items():
            if not isinstance(rows, list) or len(rows) != min(20, len(index['ids']) - 1): return False
            seen = set(); contains = self._contains(index, i)
            for row in rows:
                candidate = row.get('catalog_id')
                if candidate == index['ids'][i] or candidate not in index['lookup'] or candidate in seen: return False
                score = row.get('similaridade_semantica')
                if not isinstance(score, (int, float)) or not np.isfinite(score) or not -1 <= score <= 1: return False
                if row.get('correspondencia_palavras') is not (index['lookup'][candidate] in contains): return False
                seen.add(candidate)
        return True

    def _top(self, index, i, scores, active):
        candidates = active - {i}
        contains = self._contains(index, i) & candidates
        def take(pool, count):
            if not pool or count <= 0: return []
            array = np.array(sorted(pool), dtype=int)
            if len(array) > count:
                array = array[np.argpartition(-scores[array], count - 1)[:count]]
            return sorted(array.tolist(), key=lambda j: (-float(scores[j]), index['ids'][j]))[:count]
        selected = take(contains, 20)
        selected += take(candidates - contains, 20 - len(selected))
        return [{'catalog_id': index['ids'][j], 'similaridade_semantica': float(np.clip(scores[j], -1, 1)),
                 'correspondencia_palavras': j in contains} for j in selected]

    def _ready(self, key):
        with self.lock:
            job = self.jobs.get(key)
            if job is None: raise KeyError('Análise não está na memória. Solicite novamente para recuperar o cache.')
            return dict(job)

    def status(self, key, active_ids=None):
        job = self._ready(key)
        result = {name: value for name, value in job.items() if name != 'index'}
        if job['status'] != 'READY': return result
        index = job['index']
        active = set(range(len(index['ids']))) if active_ids is None else {index['lookup'][value] for value in active_ids}
        scope = frozenset(active)
        cached = index.get('last_ranking')
        if cached is not None and cached[0] == scope:
            result['rows'] = cached[1]
            result['algorithm'] = 'blocked-exact-cosine + inverted-index + revision-cache'
            return result
        rows = []
        for i in sorted(active):
            best = next((row for row in index['best'][i]
                         if row['catalog_id'] != index['ids'][i] and index['lookup'].get(row['catalog_id']) in active), None)
            if best is None and len(active) > 1:
                neighbors = self.neighbors(key, index['ids'][i], active_ids)
                best = neighbors[0] if neighbors else None
            rows.append({'catalog_id': index['ids'][i], 'pair': best})
        result['rows'] = rows
        index['last_ranking'] = (scope, rows)
        result['algorithm'] = 'blocked-exact-cosine + inverted-index + revision-cache'
        return result

    def neighbors(self, key, selected, active_ids=None):
        job = self._ready(key)
        if job['status'] != 'READY': raise ValueError('Aguarde a análise terminar.')
        index = job['index']; i = index['lookup'][selected]
        active = set(range(len(index['ids']))) if active_ids is None else {index['lookup'][value] for value in active_ids}
        scores = index['weights'][i, 0] * (index['activity'] @ index['activity'][i])
        scores += index['weights'][i, 1] * (index['context'] @ index['context'][i])
        return self._top(index, i, scores, active)
