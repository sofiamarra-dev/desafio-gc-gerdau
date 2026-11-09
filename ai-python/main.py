"""API de importação, busca semântica e status do catálogo."""
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .core import Catalog
from .embedder import MiniLMEmbedder
from .abbreviations import prepare_dictionary
from .review_index import ReviewIndex
from .identical_records import find_identical_records


class CatalogItem(BaseModel):
    catalog_id: str = ''
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


class SyncRequest(BaseModel):
    items: list[CatalogItem] = Field(max_length=50_000)
    revision: str = Field(min_length=1, max_length=100)


class DictionaryEntry(BaseModel):
    code: str = Field(min_length=1, max_length=16, pattern=r'^[A-Za-z0-9]+$')
    meaning: str = Field(min_length=1, max_length=80)
    variants: list[str] = Field(default_factory=list, max_length=100)
    active: bool = True


class DictionaryRequest(BaseModel):
    version: str = Field(min_length=1, max_length=100)
    entries: list[DictionaryEntry] = Field(max_length=1000)


class DictionaryPreviewRequest(DictionaryRequest):
    text: str = Field(min_length=1, max_length=300)


class CompareItem(CatalogItem):
    descricao_item: str = Field(min_length=1, max_length=300)


class CompareRequest(BaseModel):
    item: CompareItem
    candidates: list[CompareItem] = Field(max_length=50_000)
    dictionary: DictionaryRequest


class ReviewStart(BaseModel):
    items: list[CompareItem] = Field(max_length=50_000)
    dictionary: DictionaryRequest


class IdentityItem(CompareItem):
    cleanup_passes: str = '0'
    required_cleanup_passes: str = '1'
    cleaned_at: str = ''
    created_at: str = ''


class IdentityRequest(BaseModel):
    items: list[IdentityItem] = Field(max_length=50_000)
    dictionary: DictionaryRequest


class StandardizeRequest(BaseModel):
    descriptions: list[str] = Field(max_length=50_000)
    dictionary: DictionaryRequest


class ReviewScope(BaseModel):
    active_ids: list[str] | None = Field(default=None, max_length=50_000)
    selected: str | None = None


def description_preview(parts):
    return {'original': parts.original, 'activity': parts.activity,
            'qualifiers': list(parts.qualifier_texts), 'standardized': parts.standardized,
            'needs_review': parts.needs_review, 'dictionary_version': parts.dictionary_version}


def create_app(embedder_factory=MiniLMEmbedder, data_dir=None):
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        directory = Path(data_dir) if data_dir is not None else Path(
            os.environ.get('PYTHON_DATA_DIR', str(Path(__file__).parent / 'data'))
        )
        app.state.catalog = Catalog(embedder_factory(), directory)
        app.state.review_index = ReviewIndex(app.state.catalog.embedder, directory)
        yield
        app.state.review_index.worker.shutdown(wait=True)

    api = FastAPI(title='Busca de serviços — MiniLM', lifespan=lifespan)

    @api.get('/status')
    def status():
        catalog = api.state.catalog
        with catalog.lock:
            return {'ready': not catalog.requires_reindex, 'mode': 'local', 'model': catalog.embedder.name,
                    'size': len(catalog.items), 'requires_reindex': catalog.requires_reindex,
                    'revision': catalog.revision,
                    'dictionary_version': catalog.dictionary.version,
                    'search_strategy': 'activity_qualifiers',
                    'ranking_strategy': 'semantic_plus_activity_words',
                    'activity_text_bonus': catalog.activity_text_bonus,
                    'weights': {'activity': catalog.description_weight,
                                'qualifiers': catalog.context_weight}}

    @api.post('/index')
    def index(request: IndexRequest):
        try:
            with api.state.catalog.lock:
                count = api.state.catalog.replace([item.model_dump() for item in request.items])
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        return {'imported': count, 'mode': 'local', 'model': api.state.catalog.embedder.name}

    @api.put('/dictionary')
    def update_dictionary(request: DictionaryRequest):
        catalog = api.state.catalog
        try:
            with catalog.lock:
                catalog.update_dictionary([entry.model_dump() for entry in request.entries], request.version)
                return {'dictionary_version': catalog.dictionary.version,
                        'requires_reindex': catalog.requires_reindex}
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

    @api.post('/dictionary/preview')
    def preview_dictionary(request: DictionaryPreviewRequest):
        try:
            dictionary, _, _ = prepare_dictionary([entry.model_dump() for entry in request.entries], request.version)
            parts = dictionary.split(request.text)
            return description_preview(parts)
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

    @api.post('/dictionary/standardize')
    def standardize(request: StandardizeRequest):
        try:
            dictionary, _, _ = prepare_dictionary(
                [entry.model_dump() for entry in request.dictionary.entries], request.dictionary.version)
            descriptions = []
            for text in request.descriptions:
                if not text.strip() or len(text) > 300:
                    raise ValueError('Descrição obrigatória com até 300 caracteres.')
                standardized = dictionary.split(text).standardized
                if not standardized or len(standardized) > 300:
                    raise ValueError('A descrição padronizada excede o limite de 300 caracteres.')
                descriptions.append(standardized)
            return {'dictionary_version': request.dictionary.version, 'descriptions': descriptions}
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

    @api.get('/catalog')
    def export_catalog():
        # Migração explícita da base antiga; o Java decide quando gravá-la no PostgreSQL.
        with api.state.catalog.lock:
            return {'items': list(api.state.catalog.items)}

    @api.post('/sync')
    def sync(request: SyncRequest):
        try:
            with api.state.catalog.lock:
                count = api.state.catalog.replace([item.model_dump() for item in request.items],
                                                 revision=request.revision, allow_empty=True)
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        return {'imported': count, 'revision': request.revision}

    @api.post('/search')
    def search(request: SearchRequest):
        catalog = api.state.catalog
        try:
            # A explicação e os resultados usam a mesma versão do dicionário.
            with catalog.lock:
                results = catalog.search(request.query, request.limit)
                parts = catalog.dictionary.split(request.query)
                return {'query': request.query, 'total': len(results), 'results': results,
                        'query_parts': description_preview(parts),
                        'mode': 'local', 'model': catalog.embedder.name}
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

    @api.post('/compare')
    def compare(request: CompareRequest):
        try:
            return api.state.catalog.compare(
                request.item.model_dump(), [row.model_dump() for row in request.candidates],
                [entry.model_dump() for entry in request.dictionary.entries], request.dictionary.version)
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

    @api.post('/identical-records')
    def identical_records(request: IdentityRequest):
        try:
            return find_identical_records(
                [row.model_dump() for row in request.items],
                [entry.model_dump() for entry in request.dictionary.entries],
                request.dictionary.version)
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

    @api.post('/review-index')
    def review_start(request: ReviewStart):
        try:
            return api.state.review_index.start([row.model_dump() for row in request.items],
                [entry.model_dump() for entry in request.dictionary.entries], request.dictionary.version)
        except ValueError as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc

    @api.post('/review-index/{key}')
    def review_status(key: str, request: ReviewScope):
        try:
            if request.selected:
                return {'results': api.state.review_index.neighbors(key, request.selected, request.active_ids)}
            return api.state.review_index.status(key, request.active_ids)
        except KeyError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc

    return api


app = create_app()
