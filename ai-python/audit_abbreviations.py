"""Prévia offline, sem carregar MiniLM e sem escrever no catálogo original."""
import argparse
from collections import Counter
import hashlib
import html
import json
from pathlib import Path
import re

from .abbreviations import INITIAL_DICTIONARY, key


def review_reasons(parts):
    """Sinais para revisão humana, não classificação automática de erros."""
    reasons = []
    remaining_tokens = set(key(parts.activity))
    if parts.needs_review:
        reasons.append('Sem atividade identificável')
    for term in ('sem', 'esp', 'fds', 'adm'):
        if term in remaining_tokens:
            reasons.append(f'Termo dependente de contexto: {term.upper()}')
    if any(match.code == 'SV' and key(parts.original[:match.start])[-1:] in
           (('de',), ('do',), ('dos',)) for match in parts.matches):
        reasons.append('Serviço pode fazer parte do nome da atividade')
    if '%' in parts.activity and parts.matches:
        reasons.append('Percentual permaneceu na atividade')
    if parts.matches and (re.search(r'\(\s*\)|\[\s*\]', parts.activity)
                          or parts.activity.startswith(('-', '/', '.'))
                          or parts.activity.endswith(('-', '/', '.'))):
        reasons.append('Pontuação residual após separação')
    if any(word in remaining_tokens for word in ('horaextra', 'not', 'dom', 'fer')):
        reasons.append('Possível qualificador ainda não cadastrado')
    return reasons


def audit(items):
    rows = []
    qualifiers = Counter()
    reasons_count = Counter()
    for position, item in enumerate(items, 1):
        description = item.get('descricao_item', '')
        if not isinstance(description, str):
            raise ValueError(f'Descrição inválida na linha {position}.')
        parts = INITIAL_DICTIONARY.split(description)
        reasons = review_reasons(parts)
        qualifiers.update(parts.qualifiers)
        reasons_count.update(reasons)
        rows.append({
            'row': position,
            'catalog_id': item.get('catalog_id', ''),
            'codigo_servico': item.get('codigo_servico', ''),
            'original': parts.original,
            'activity': parts.activity,
            'qualifiers': list(parts.qualifiers),
            'qualifier_texts': list(parts.qualifier_texts),
            'percentages': [{'code': match.code, 'percentage': match.percentage}
                            for match in parts.matches if match.percentage is not None],
            'standardized': parts.standardized,
            'protected_expressions': list(parts.protected_expressions),
            'review_reasons': reasons,
        })
    return {
        'dictionary_version': INITIAL_DICTIONARY.version,
        'total': len(rows),
        'with_matches': sum(bool(row['qualifiers']) for row in rows),
        'with_percentages': sum(bool(row['percentages']) for row in rows),
        'flagged_for_review': sum(bool(row['review_reasons']) for row in rows),
        'with_protected_expressions': sum(bool(row['protected_expressions']) for row in rows),
        'qualifier_counts': dict(qualifiers),
        'review_reason_counts': dict(reasons_count),
        'rows': rows,
    }


def render_report(report):
    escape = lambda value: html.escape(str(value), quote=True)
    # Exemplos distribuídos entre os motivos, evitando uma amostra só do começo.
    selected = {}
    for reason in report['review_reason_counts']:
        candidates = [row for row in report['rows'] if reason in row['review_reasons']]
        for row in candidates[:10]:
            selected[row['row']] = row
    for row in [row for row in report['rows'] if row['qualifiers'] and not row['review_reasons']][:15]:
        selected[row['row']] = row
    for row in [row for row in report['rows'] if row['protected_expressions']][:10]:
        selected[row['row']] = row
    for row in [row for row in report['rows'] if row['percentages']][:15]:
        selected[row['row']] = row
    body = ''.join('<tr>' + ''.join(f'<td>{escape(value)}</td>' for value in (
        row['row'], row['codigo_servico'], row['original'], row['activity'],
        ' | '.join(row['qualifier_texts']), '; '.join(row['protected_expressions']),
        '; '.join(row['review_reasons']) or 'Sem alerta heurístico',
    )) + '</tr>' for row in selected.values())
    counts = ''.join(f'<li>{escape(reason)}: <strong>{count}</strong></li>'
                     for reason, count in report['review_reason_counts'].items())
    return f'''<!doctype html><html lang="pt-BR"><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Revisão da separação de descrições</title>
<style>body{{font:16px system-ui;margin:32px;color:#172b4d}}h1{{color:#004b87}}
table{{border-collapse:collapse;width:100%;font-size:14px}}td,th{{padding:12px;border:1px solid #ccd5df;text-align:left}}
th{{background:#e8f1fa}}tr:nth-child(even){{background:#f7f9fb}}.table{{overflow:auto}}
code{{overflow-wrap:anywhere}}p{{max-width:1000px}}</style>
<h1>Revisão da separação de descrições</h1>
<p>Prévia offline do catálogo local. Nenhum registro ou embedding foi alterado.
O banco SQL não foi consultado; esta cópia pode estar desatualizada.</p>
<p><strong>{report['total']}</strong> registros;
<strong>{report['with_matches']}</strong> com expressões reconhecidas;
<strong>{report['flagged_for_review']}</strong> com sinais para revisão.</p>
<p><strong>{report['with_protected_expressions']}</strong> registros com expressões de atividade protegidas.</p>
<p><strong>{report['with_percentages']}</strong> registros com percentuais associados a adicionais.</p>
<p>Os alertas são heurísticos e podem se sobrepor. Ausência de alerta não significa
separação correta. Estes números não medem qualidade da busca.</p>
<ul>{counts}</ul>
<p>Versão: {escape(report['dictionary_version'])}. Revisão do catálogo:
{escape(report.get('catalog_revision'))}. SHA-256 da fonte:
<code>{escape(report.get('source_sha256'))}</code>.</p>
<h2>Exemplos para revisão</h2><p>Amostra por motivo de alerta e exemplos sem alerta.
O arquivo JSON ao lado contém todos os registros e os resultados derivados.</p>
<div class="table"><table><thead><tr><th>Linha</th><th>Código</th><th>Original</th>
<th>Atividade proposta</th><th>Qualificadores</th><th>Expressões protegidas</th><th>Revisão</th></tr></thead>
<tbody>{body}</tbody></table></div>
<h2>Decisões antes de integrar</h2><ol>
<li>Revisar e ampliar a lista inicial de expressões protegidas (já inclui “serviços gerais”).</li>
<li>Interpretar siglas ambíguas pelo contexto, sem cadastrar SEM ou ESP globalmente.</li>
<li>Definir como tratar percentuais e horários sem remover medidas como 20 MM.</li>
<li>Após revisar a separação, comparar buscas com atividade somente e pesos 90/10.</li>
</ol></html>'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--catalog', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True,
                        help='Diretório novo, para não sobrescrever relatórios anteriores.')
    args = parser.parse_args()
    source = args.catalog.read_bytes()
    data = json.loads(source.decode('utf-8'))
    report = audit(data['items'])
    report.update(source_path=str(args.catalog.resolve()),
                  source_sha256=hashlib.sha256(source).hexdigest(),
                  catalog_revision=data.get('revision'))
    args.output_dir.mkdir(parents=True, exist_ok=False)
    (args.output_dir / 'review.json').write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    (args.output_dir / 'review.html').write_text(render_report(report), encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'rows'}, ensure_ascii=True))


if __name__ == '__main__':
    main()
