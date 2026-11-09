"""Agrupamento determinístico de registros idênticos, sem consultar embeddings."""
from datetime import datetime, timezone
from fractions import Fraction

from .abbreviations import prepare_dictionary
from .comparison import identity_value


# Regra da limpeza: fornecedor não diferencia serviços equivalentes.
# A identidade da criação em massa permanece definida em comparison.py.
CLEANUP_IDENTITY_FIELDS = ('grupo_servico', 'unidade_medida', 'unidade_descricao')


def cleanup_identity(item, split):
    return (identity_value(split.standardized),
            *(identity_value(item.get(field, '')) for field in CLEANUP_IDENTITY_FIELDS))


def _integer(value, default):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _keeper_priority(row):
    required = max(1, _integer(row.get('required_cleanup_passes'), 1))
    passes = min(required, max(0, _integer(row.get('cleanup_passes'), 0)))
    verified = passes >= required or bool(str(row.get('cleaned_at') or '').strip())
    try:
        created = datetime.fromisoformat(str(row.get('created_at') or '').replace('Z', '+00:00'))
        if created.tzinfo is None:
            created = created.replace(tzinfo=timezone.utc)
        created = created.astimezone(timezone.utc)
    except (TypeError, ValueError, OverflowError):
        created = datetime.max.replace(tzinfo=timezone.utc)
    return (-int(verified), -Fraction(passes, required), created, row['catalog_id'])


def find_identical_records(items, entries, version):
    """Hash cadastral da limpeza, ignorando ID, código do serviço e fornecedor."""
    dictionary, _, _ = prepare_dictionary(entries, version)
    ids = set()
    groups = {}
    for item in items:
        identifier = item.get('catalog_id')
        if not isinstance(identifier, str) or not identifier.strip() or identifier in ids:
            raise ValueError('A lista contém identificadores ausentes ou repetidos.')
        ids.add(identifier)
        description = item.get('descricao_item', '')
        if not description.strip():
            continue
        split = dictionary.split(description)
        if split.needs_review:
            continue
        groups.setdefault(cleanup_identity(item, split), []).append(item)

    result = []
    removed = 0
    for matching in groups.values():
        if len(matching) < 2:
            continue
        kept = min(matching, key=_keeper_priority)['catalog_id']
        duplicates = [row['catalog_id'] for row in matching if row['catalog_id'] != kept]
        result.append({'kept_id': kept, 'duplicate_ids': duplicates})
        removed += len(duplicates)
    return {'groups': result, 'total_removed': removed}
