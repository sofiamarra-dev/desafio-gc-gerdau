"""Correspondência parcial de palavras inteiras da atividade, sem novos modelos."""
from collections import Counter, defaultdict
import math
import numpy as np
from .embedder import normalized

STOPWORDS = frozenset('a o as os um uma uns umas de da do das dos e em no na nos nas para por com sem ao aos a'.split())


def terms(text):
    return [word for word in normalized(text).split() if len(word) >= 2 and not word.isdigit() and word not in STOPWORDS]


class ActivityRanking:
    def __init__(self, activities):
        # Fornecedores repetidos não fazem a profissão parecer mais comum.
        unique = list(dict.fromkeys(normalized(text) for text in activities))
        counters = [Counter(terms(text)) for text in unique]
        self.postings = defaultdict(list)
        self.lengths = np.array([sum(c.values()) for c in counters], dtype=np.float32)
        self.average = float(self.lengths.mean()) if len(self.lengths) and self.lengths.mean() else 1.0
        for i, counter in enumerate(counters):
            for word, count in counter.items(): self.postings[word].append((i, count))
        index = {text:i for i,text in enumerate(unique)}
        self.rows = np.array([index[normalized(text)] for text in activities], dtype=int)
        self.count = len(unique)

    def scores(self, query):
        scores = np.zeros(self.count, dtype=np.float32)
        for word in set(terms(query)):
            postings = self.postings.get(word, [])
            if not postings: continue
            idf = math.log(1 + (self.count - len(postings) + .5) / (len(postings) + .5))
            for i, frequency in postings:
                norm = 1.2 * (.25 + .75 * self.lengths[i] / self.average)
                scores[i] += idf * frequency * 2.2 / (frequency + norm)
        maximum = float(scores.max()) if len(scores) else 0
        return (scores / maximum)[self.rows] if maximum else np.zeros(len(self.rows), dtype=np.float32)
