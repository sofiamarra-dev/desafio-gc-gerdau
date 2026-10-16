"""Embeddings reais gerados pelo MiniLM, localmente em CPU."""
import re
import unicodedata
from typing import Sequence

import numpy as np

MODEL_NAME = 'sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2'


def normalized(text: str) -> str:
    # muda os textos para que não possuam acento e coisas parecidas, o código final é gerado com os acentos normalmente
    text = unicodedata.normalize('NFKD', text.lower())
    text = ''.join(c for c in text if not unicodedata.combining(c))
    return re.sub(r'[^a-z0-9]+', ' ', text).strip()


def unit_vector(v: np.ndarray) -> np.ndarray:
    v = np.asarray(v, dtype=np.float32)
    norms = np.linalg.norm(v, axis=1, keepdims=True)
    return v / np.maximum(norms, 1e-12)


class MiniLMEmbedder:
    name = MODEL_NAME
    dimension = 384

    def __init__(self):
        from sentence_transformers import SentenceTransformer
        # Carregado uma vez na inicialização, reutilizado nas consultas.
        self.model = SentenceTransformer(MODEL_NAME, device='cpu')

    def embed(self, texts: Sequence[str]) -> np.ndarray:
        if not texts:
            return np.zeros((0, self.dimension), dtype=np.float32)
        return self.model.encode(
            list(texts), batch_size=16, normalize_embeddings=True,
            convert_to_numpy=True, show_progress_bar=False,
        ).astype(np.float32)
