"""Separação conservadora da descrição para indexação e consulta."""
from dataclasses import dataclass, replace
import hashlib
import json
import re
import unicodedata


# Pontuação tem seu próprio token: não juntamos expressões através de vírgulas.
TOKEN = re.compile(r"(?:\w|[\u0300-\u036f])+|[^\w\s]")
PERCENTAGE = re.compile(r'[ \t\u00a0\u202f]+([0-9]+(?:[.,][0-9]+)?)[ \t\u00a0\u202f]*%(?![\w%])')
SPLITTER_VERSION = 'percentages-2'
HE_DOTTED_VARIANTS = ('H. EXTRA', 'H. EXTRAS')
PERCENTAGE_CODES = frozenset(('HE', 'NOT', 'PERI', 'INSA'))


def key(text: str) -> tuple[str, ...]:
    text = unicodedata.normalize('NFKD', text.casefold())
    text = ''.join(char for char in text if not unicodedata.combining(char))
    return tuple(TOKEN.findall(text))


@dataclass(frozen=True)
class Abbreviation:
    code: str
    meaning: str
    variants: tuple[str, ...] = ()


@dataclass(frozen=True)
class Match:
    original: str
    code: str
    start: int
    end: int
    percentage: str | None = None

    @property
    def label(self) -> str:
        return f'{self.code} {self.percentage}%' if self.percentage is not None else self.code


@dataclass(frozen=True)
class DescriptionParts:
    original: str
    activity: str
    qualifiers: tuple[str, ...]
    standardized: str
    matches: tuple[Match, ...]
    dictionary_version: str
    protected_expressions: tuple[str, ...] = ()

    @property
    def qualifier_texts(self) -> tuple[str, ...]:
        return tuple(dict.fromkeys(match.label for match in self.matches))

    @property
    def needs_review(self) -> bool:
        # Uma descrição contendo apenas siglas/pontuação não identifica a atividade.
        return not any(char.isalnum() for char in self.activity)


def clean_activity(description: str, matches: list[Match]) -> str:
    """Limpa apenas separadores afetados pela extração, usando posições originais."""
    chars = list(description)
    for match in matches:
        chars[match.start:match.end] = [None] * (match.end - match.start)

    # O ponto imediatamente após um trecho extraído pode ser seu terminador:
    # SV. -> vazio; NOT. -> vazio. Não tocamos em Aux., NR.35 ou decimais.
    # Também preservamos .5 após uma sigla, pois pode ser um valor técnico.
    for match in matches:
        end = match.end
        if end < len(chars) and chars[end] == '.':
            following = description[end + 1:end + 2]
            if not following or (not following.isalnum() and following != '.'):
                chars[end] = None

    # Parênteses/colchetes só desaparecem se a extração esvaziou seu conteúdo.
    stack = []
    for i, char in enumerate(description):
        if char in '([':
            stack.append((char, i))
        elif char in ')]' and stack:
            opening, start = stack.pop()
            if (opening, char) not in (('(', ')'), ('[', ']')):
                continue
            inside = chars[start + 1:i]
            if any(c is None for c in inside) and all(
                    c is None or c.isspace() or c in '-–—/' for c in inside):
                chars[start:i + 1] = [None] * (i + 1 - start)

    # Runs que incluem uma remoção podem ter hífens de ligação órfãos.
    # Hífens internos não tocados, medidas e sinais numéricos são preservados.
    i = 0
    while i < len(chars):
        if chars[i] is not None and not chars[i].isspace() and chars[i] not in '-–—':
            i += 1
            continue
        start = i
        while i < len(chars) and (chars[i] is None or chars[i].isspace() or chars[i] in '-–—'):
            i += 1
        if any(c is None for c in chars[start:i]):
            for j in range(start, i):
                if chars[j] in ('-', '–', '—'):
                    numeric_sign = (j + 1 < len(description) and description[j + 1].isdigit()
                                    and (j == 0 or description[j - 1].isspace()))
                    if not numeric_sign:
                        chars[j] = None
    return ' '.join(''.join(c if c is not None else ' ' for c in chars).split())


class AbbreviationDictionary:
    def __init__(self, entries: tuple[Abbreviation, ...], version: str,
                 protected_phrases: tuple[str, ...] = ()):
        if not version.strip():
            raise ValueError('Informe a versão do dicionário.')
        self.version = version
        self.entries = entries
        self.protected_phrases = protected_phrases
        self._protected = {key(phrase) for phrase in protected_phrases}
        if () in self._protected:
            raise ValueError('Uma expressão protegida não pode ser vazia.')
        self._max_protected = max(map(len, self._protected), default=0)
        self._aliases: dict[tuple[str, ...], str] = {}
        codes = set()
        for entry in entries:
            code_key = key(entry.code)
            if len(code_key) != 1 or not entry.code.isalnum() or not key(entry.meaning):
                raise ValueError('Sigla ou significado inválido.')
            if code_key in codes:
                raise ValueError(f'Sigla já cadastrada: {entry.code}')
            codes.add(code_key)
            canonical = entry.code.upper()
            # O significado oficial também é uma expressão reconhecida.
            for alias in (entry.code, entry.meaning, *entry.variants):
                alias_key = key(alias)
                if not alias_key:
                    raise ValueError('Uma variante não pode ser vazia.')
                owner = self._aliases.get(alias_key)
                if owner is not None and owner != canonical:
                    raise ValueError(f'Expressão {alias!r} já pertence a {owner}.')
                self._aliases[alias_key] = canonical
        self._max_words = max(map(len, self._aliases), default=0)

    def split(self, description: str) -> DescriptionParts:
        tokens = [token for token in TOKEN.finditer(description) if key(token.group())]
        normalized_tokens = [key(token.group())[0] for token in tokens]
        protected_positions = set()
        protected_expressions = []
        for i in range(len(tokens)):
            if i in protected_positions:
                continue
            for size in range(min(self._max_protected, len(tokens) - i), 0, -1):
                if tuple(normalized_tokens[i:i + size]) in self._protected:
                    protected_positions.update(range(i, i + size))
                    protected_expressions.append(description[tokens[i].start():tokens[i + size - 1].end()])
                    break
        matches = []
        i = 0
        while i < len(tokens):
            # Tenta a expressão mais longa antes das menores, na mesma posição.
            for size in range(min(self._max_words, len(tokens) - i), 0, -1):
                if any(position in protected_positions for position in range(i, i + size)):
                    continue
                code = self._aliases.get(tuple(normalized_tokens[i:i + size]))
                if code is not None:
                    start, end = tokens[i].start(), tokens[i + size - 1].end()
                    matches.append(Match(description[start:end], code, start, end))
                    i += size
                    break
            else:
                i += 1

        # Uma expressão completa como "hora extra 50%" explicita o adicional.
        # Abreviações encadeadas, como HE NOT 50%, continuam ambíguas.
        explicit_he = {key(term) for term in ('hora extra', 'horas extras', *HE_DOTTED_VARIANTS)}
        grouped_percentages = set()
        # Associa percentuais somente a adicionais adjacentes e inequívocos.
        for position, match in enumerate(matches):
            if match.code not in PERCENTAGE_CODES:
                continue
            percentage = PERCENTAGE.match(description, match.end)
            if percentage is None:
                continue
            previous = position - 1
            boundary = match.start
            ambiguous = False
            while previous >= 0 and not description[matches[previous].end:boundary].strip():
                candidate = matches[previous]
                if candidate.code in PERCENTAGE_CODES and candidate.percentage is None:
                    ambiguous = True
                    break
                boundary = candidate.start
                previous -= 1
            explicit = match.code == 'HE' and key(match.original) in explicit_he
            if not ambiguous or explicit:
                if ambiguous:
                    grouped_percentages.add(position)
                end = percentage.end()
                matches[position] = replace(match, end=end, original=description[match.start:end],
                                            percentage=percentage.group(1).replace('.', ','))

        standardized = []
        cursor = 0
        for position, match in enumerate(matches):
            remaining = description[cursor:match.start]
            # O agrupamento preserva a associação ao reler o texto padronizado.
            label = f'({match.label})' if position in grouped_percentages else match.label
            standardized.extend((remaining, label))
            cursor = match.end
        standardized.append(description[cursor:])
        return DescriptionParts(
            original=description,
            activity=clean_activity(description, matches),
            qualifiers=tuple(dict.fromkeys(match.code for match in matches)),
            standardized=' '.join(''.join(standardized).split()),
            matches=tuple(matches),
            dictionary_version=self.version,
            protected_expressions=tuple(protected_expressions),
        )


# Semente conservadora para testes; não é um cadastro aprovado em produção.
# SEM, ANO, ADM etc. podem carregar o sentido principal em certas descrições.
INITIAL_DICTIONARY = AbbreviationDictionary((
    Abbreviation('SV', 'serviço', ('serviços',)),
    Abbreviation('HE', 'hora extra', ('horas extras', *HE_DOTTED_VARIANTS)),
    Abbreviation('NOT', 'noturno', ('noturna', 'noturnos', 'noturnas',
                                  'hora noturna', 'horas noturnas', 'HNOT')),
    Abbreviation('HNOR', 'hora normal', ('horas normais',)),
    Abbreviation('DOMF', 'domingo / feriado', (
        'DOM.FER', 'DOM/FER', 'DOM FER', 'DOM E FER',
        'domingo e feriado', 'domingos e feriados', 'domingos / feriados',
        'DOM E FERIADOS', 'DOM E FERIADO', 'DOM/FERIADO', 'DOM FERIADO',
        'DOMINGO/FER',
    )),
    Abbreviation('PERI', 'periculosidade'),
    Abbreviation('INSA', 'insalubridade'),
), version='search-8', protected_phrases=(
    'serviços gerais', 'serviço geral', 'sv gerais', 'sv. gerais', 'sv geral', 'sv. geral',
    'líder de sv', 'líder de serviço', 'líder de serviços',
) + tuple(f'{prefix} {term}' for prefix in ('sem', 'sem adicional de', 'não inclui', 'não incluir')
          for term in ('insalubridade', 'insa', 'periculosidade', 'peri',
                       'hora extra', 'horas extras', *HE_DOTTED_VARIANTS, 'he', 'noturno', 'noturna', 'not', 'hnot')))


def prepare_dictionary(entries: list[dict], version: str):
    """Valida inclusive variantes inativas; retorna um dicionário independente."""
    if not isinstance(version, str) or not version.strip() or len(version) > 100:
        raise ValueError('Versão de dicionário inválida.')
    if not isinstance(entries, list) or len(entries) > 1000:
        raise ValueError('Máximo de 1.000 siglas no dicionário.')
    clean = []
    for raw in entries:
        if not isinstance(raw, dict):
            raise ValueError('Entrada de dicionário inválida.')
        code, meaning = raw.get('code'), raw.get('meaning')
        variants, active = raw.get('variants', []), raw.get('active', True)
        if not isinstance(code, str) or not re.fullmatch(r'[A-Za-z0-9]{1,16}', code):
            raise ValueError('A sigla deve conter de 1 a 16 letras ou números.')
        if not isinstance(meaning, str) or not meaning.strip() or len(meaning) > 80:
            raise ValueError('Informe um significado com até 80 caracteres.')
        if not isinstance(variants, list) or len(variants) > 100 or not isinstance(active, bool):
            raise ValueError('Variantes ou situação da sigla inválidas.')
        if any(not isinstance(v, str) or not v.strip() or len(v) > 80 for v in variants):
            raise ValueError('Cada variante deve conter de 1 a 80 caracteres.')
        for phrase in (meaning, *variants):
            if not any(c.isalnum() for c in phrase) or any(unicodedata.category(c) == 'Cc' for c in phrase):
                raise ValueError('Use expressões com letras ou números, sem quebras de linha.')
        for phrase in (code, meaning, *variants):
            compact = ''.join(c for token in key(phrase) for c in token if c.isalnum()).upper()
            if compact in {'ESP', 'ADM'}:
                raise ValueError('ESP e ADM isolados são ambíguos. Use apenas expressões completas de qualificadores confirmados.')
        clean.append({'code': code.upper(), 'meaning': meaning.strip(),
                      'variants': sorted(set(v.strip() for v in variants)), 'active': active})
    clean.sort(key=lambda entry: entry['code'])
    all_entries = tuple(Abbreviation(e['code'], e['meaning'], tuple(e['variants'])) for e in clean)
    # Valida propriedade das expressões mesmo quando a sigla está inativa.
    AbbreviationDictionary(all_entries, version)
    active_entries = tuple(entry for entry, raw in zip(all_entries, clean) if raw['active'])
    negative_phrases = tuple(f'{prefix} {alias}' for entry in active_entries
                            for alias in (entry.code, entry.meaning, *entry.variants)
                            for prefix in ('sem', 'sem adicional de', 'não inclui', 'não incluir'))
    dictionary = AbbreviationDictionary(active_entries, version,
                                       INITIAL_DICTIONARY.protected_phrases + negative_phrases)
    fingerprint = hashlib.sha256(json.dumps(clean, ensure_ascii=False, sort_keys=True).encode('utf-8')).hexdigest()
    return dictionary, clean, fingerprint
