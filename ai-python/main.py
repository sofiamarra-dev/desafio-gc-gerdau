"""API de importação, busca semântica e status do catálogo."""
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .core import Catalog
from .embedder import MiniLMEmbedder


class CatalogItem(BaseModel):
    grupo_servico: str = ''
    codigo_fornecedor: str = ''
    codigo_servico: str = ''
    descricao_item: str = ''
    unidade_medida: str = ''
    unidade_descricao: str = ''


class IndexRequest(BaseModel):
    items: list[CatalogItem] = Field(min_length=1, max_length=50_000)


class SearchRequest(BaseModel):
    query: str = Field(min_length=1, max_length=300)
    limit: int = Field(default=20, ge=1, le=20)


def create_app(embedder_factory=MiniLMEmbedder, data_dir=None):
    # A fábrica permite testar as rotas sem baixar o modelo a cada teste.
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        directory = Path(data_dir) if data_dir is not None else Path(
            os.environ.get('PYTHON_DATA_DIR', str(Path(__file__).parent / 'data'))
        )
        app.state.catalog = Catalog(embedder_factory(), directory)
        yield

    api = FastAPI(title='Busca de serviços — MiniLM', lifespan=lifespan)

    @api.get('/status')
    def status():
        catalog = api.state.catalog
        with catalog.lock:
            return {'ready': True, 'mode': 'local', 'model': catalog.embedder.name,
                    'size': len(catalog.items)}

    @api.post('/index')
    def index(request: IndexRequest):
        try:
            count = api.state.catalog.replace([item.model_dump() for item in request.items])
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        return {'imported': count, 'mode': 'local', 'model': api.state.catalog.embedder.name}

    @api.post('/search')
    def search(request: SearchRequest):
        try:
            results = api.state.catalog.search(request.query, request.limit)
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        return {'query': request.query, 'total': len(results), 'results': results,
                'mode': 'local', 'model': api.state.catalog.embedder.name}

    return api


app = create_app()
