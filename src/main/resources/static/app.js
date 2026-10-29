
function $(id){ // fazemos isso pra não ter que ficar sempre escrevendo muito texto para pegar o elemento
    return document.getElementById(id);
}

const notice = $('notice');

let numeroDeCaracteresLivres = 40



// nao esta efetivamente limitando, tambem ainda precisa ser levado em conta caso um campo
// seja selecionado depois do campo livre
function leitorDeFormulario(){
    let codigo = ""

    codigo = $('campoLivre').value

    if ($('servico').checked){
        codigo = 'SV ' + codigo
    }

    if ($('horaExtra').checked){
        codigo += ' HE'
    }

    if ($('periculosidade').checked){
        codigo += " PERI"
    }

    if ($('insalubridade').checked){
        codigo += " INSA"
    }

    if ($('horaNoturna').checked){
        codigo += " NOT"
    }

    if ($('horaNormal').checked){
        codigo += " HNOR"
    }

    if ($('domingoFeriado').checked){
        codigo += " DOMF"
    }

    if ($('semanal').checked){
        codigo += " SEM"
    }

    if ($('mensal').checked){
        codigo += " MEN"
    }

    if ($('anual').checked){
        codigo += " ANO"
    }

    if ($('segurancaDoTrabalho').checked){
        codigo += " FDS"
    }

    if ($('administracao').checked){
        codigo += " ADM"
    }

    if ($('engenhariaDeProducao').checked){
        codigo += " ESP"
    }

    if ($('campoPercentualAdicional').value !== "") {
        codigo += ' '+$('campoPercentualAdicional').value + "%";
    }

    codigo += ' ' +$('campoMedida').value

    codigo = codigo.trim()

    codigo = codigo.toUpperCase()

    $('search-input').value = codigo

    numeroDeCaracteresLivres = 40 - codigo.length

    $('characterCount').textContent = numeroDeCaracteresLivres

    $('characterCount').style.color = numeroDeCaracteresLivres == 0 ? 'red' : 'green';

    $('campoLivre').maxLength = Math.max(0, 40 - (codigo.length - $('campoLivre').value.length));

}

document.querySelectorAll('input[type="checkbox"]').forEach(function(checkbox) {
    checkbox.addEventListener("change", leitorDeFormulario);
});

$('campoLivre').addEventListener("input", function(event) {
    leitorDeFormulario()
});

$('campoMedida').addEventListener("input", function(event) {
    leitorDeFormulario()
});

$('campoPercentualAdicional').addEventListener("input", function(event) { // só aceita numeros
    leitorDeFormulario()
});

function showNotice(message, type = '') {
    notice.textContent = message;
    notice.className = `notice ${type}`;
}


function errorMessage(payload, fallback) {
    const detail = payload?.detail;

    if (Array.isArray(detail)) {
        return detail
            .map((entry) => entry.msg || JSON.stringify(entry))
            .join('; ');
    }

    return payload?.error || detail || fallback;
}


async function apiJson(path, options = {}) {
    const response = await fetch(path, options);

    let data;

    try {
        data = await response.json();
    } catch {
        data = {};
    }

    if (!response.ok) {
        throw new Error(
            errorMessage(data, `Erro HTTP ${response.status}`)
        );
    }

    return data;
}


async function refreshStatus() { // aqui pega o status da API e muda o front apartir dele
    try {
    const state = await apiJson('/api/status');
    const database = await apiJson('/api/catalog/status');

        $('catalog-count').textContent = Number(database.size).toLocaleString('pt-BR');

        const isDemo = state.mode === 'demo';

        $('mode-pill').className = `pill ${isDemo ? 'demo' : 'active'}`;

        $('mode-pill').textContent = isDemo ? '● Modo de demonstração (sem IA)' : '● Busca semântica por embeddings';

        $('footer-model').textContent = `Motor: ${state.model}`;

    } catch { // caso tenha algum erro, os valores alterados serão:
        $('catalog-count').textContent = '—';

        $('mode-pill').className = 'pill';

        $('mode-pill').textContent = '● Serviço Python indisponível';

        $('footer-model').textContent =
            'Inicie o serviço Python na porta 8001';
    }
}


function createCell(text, cls = '') { // cria uma linha na tabela
    const el = document.createElement('div');

    el.className = cls;
    el.textContent = text;

    return el;
}


function renderResults(results) {
    const tbody = $('results-body');

    tbody.replaceChildren();

    $('result-count').textContent =
        `${results.length} RESULTADOS`;

    if (results.length === 0) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');

        td.colSpan = 5;
        td.className = 'empty';

        td.textContent =
            'Nenhum código encontrado. Importe sua base XLSX e tente novamente.';

        tr.appendChild(td);
        tbody.appendChild(tr);

        return;
    }

    results.forEach((item, i) => {
        const tr = document.createElement('tr');

        const number = document.createElement('td');

        number.textContent =
            String(i + 1).padStart(2, '0');

        const service = document.createElement('td');

        service.append(
            createCell(item.codigo_servico || '—', 'code'),
            createCell(item.descricao_item || '—', 'desc'),
            createCell(item.grupo_servico || 'Sem grupo', 'group')
        );

        const supplier = document.createElement('td');

        supplier.textContent =
            item.codigo_fornecedor || '—';

        const unit = document.createElement('td');

        unit.textContent =
            item.unidade_medida || '—';

        const affinity = document.createElement('td');

        const outer = createCell('', 'score');
        const bar = createCell('', 'bar');
        const fill = document.createElement('i');

        const containsQueryWords = item.correspondencia_palavras === true;
        const percent = containsQueryWords ? 100 : Math.max(
            0,
            Math.min(100, Number(item.similaridade) * 100)
        );

        fill.style.width = `${percent}%`;

        bar.appendChild(fill);

        const score = document.createElement('strong');

        score.textContent =
            containsQueryWords ? 'contem' : `${percent.toFixed(1).replace('.', ',')}%`;

        score.title = containsQueryWords
            ? 'A descrição contém todas as palavras da consulta, ignorando acentos'
            : item.correspondencia_exata
                ? 'Código ou descrição idênticos'
                : 'Similaridade vetorial (não é probabilidade)';

        outer.append(bar, score);
        affinity.appendChild(outer);

        tr.append(
            number,
            service,
            supplier,
            unit,
            affinity
        );

        tbody.appendChild(tr);
    });
}


$('upload-form').addEventListener('submit', async (event) => { // sempre que é enviado o arquivo, executa esse codigo
    event.preventDefault();

    const file = $('file-input').files[0];

    if (!file) {
        return showNotice(
            'Selecione um arquivo XLSX.',
            'error'
        );
    }

    if (!file.name.toLowerCase().endsWith('.xlsx')) {
        return showNotice(
            'Envie um arquivo .xlsx.',
            'error'
        );
    }

    if (file.size > 15 * 1024 * 1024) {
        return showNotice(
            'O arquivo deve ter no máximo 15 MB.',
            'error'
        );
    }

    const button = $('upload-button');

    button.disabled = true;
    button.textContent = 'Lendo planilha e criando índice…';

    showNotice(
        'Indexando os registros. O tempo depende do tamanho da planilha e do modelo configurado.'
    );

    try {
        const form = new FormData();

        form.append('file', file);

        const data = await apiJson('/api/import', {
            method: 'POST',
            body: form
        });

        showNotice(
            `Adicionados ${data.imported.toLocaleString('pt-BR')} serviços da aba “${data.sheet}”. ${data.existing} já existiam; ${data.skipped} linhas ignoradas. O índice será sincronizado na próxima busca.`,
            'success'
        );

        await refreshStatus();

        renderResults([]);

        if ($('search-input').value.trim()) {
            await searchCodes();
        }

    } catch (err) {
        showNotice(err.message, 'error');

    } finally {
        button.disabled = false;

        button.innerHTML = 'Importar e indexar códigos <span>→</span>';
    }
});


async function searchCodes() {
    const query = $('search-input').value.trim();

    if (!query) {
        return showNotice(
            'Digite um código ou descrição.',
            'error'
        );
    }

    const button = $('search-button');

    button.disabled = true;
    button.textContent = 'Pesquisando…';

    try {
        const data = await apiJson('/api/search', {
            method: 'POST',

            headers: {
                'Content-Type': 'application/json'
            },

            body: JSON.stringify({ query })
        });

        renderResults(data.results);

        showNotice(
            `Pesquisa concluída: ${data.results.length} correspondência(s) encontradas.`,
            'success'
        );

    } catch (err) {
        showNotice(err.message, 'error');

    } finally {
        button.disabled = false;

        button.innerHTML =
            'Encontrar os 20 mais próximos <span>↗</span>';
    }
}


$('search-form').addEventListener('submit', (event) => {
    event.preventDefault();

    searchCodes();
});


$('file-input').addEventListener('change', (event) => {
    $('filename').textContent =
        event.target.files[0]?.name ||
        'Selecione ou arraste um XLSX';
});


for (const chip of document.querySelectorAll('[data-query]')) {
    chip.addEventListener('click', () => {
        $('search-input').value = chip.dataset.query;
        $('search-input').focus();
    });
}


const dropzone = $('dropzone');


for (const eventName of ['dragenter', 'dragover']) {
    dropzone.addEventListener(eventName, (event) => {
        event.preventDefault();

        dropzone.classList.add('drag');
    });
}


for (const eventName of ['dragleave', 'drop']) {
    dropzone.addEventListener(eventName, (event) => {
        event.preventDefault();

        dropzone.classList.remove('drag');
    });
}


dropzone.addEventListener('drop', (event) => {
    const files = event.dataTransfer.files;

    if (files.length) {
        $('file-input').files = files;
        $('filename').textContent = files[0].name;
    }
});


const screenMode = new URLSearchParams(location.search).get('modo') || 'unitario';
$('reference-import').hidden = screenMode !== 'base';
$('unit-generator').hidden = screenMode !== 'unitario';
$('add-code-button').hidden = screenMode !== 'unitario';
$('add-code-help').hidden = screenMode !== 'unitario';
if (screenMode === 'pesquisa') {
    document.querySelector('label[for="search-input"]').textContent = 'Código ou descrição para pesquisar';
    $('search-input').placeholder = 'Descreva o serviço que procura';
}
let saveRequestId = crypto.randomUUID();
let saveRequestBody = null;
$('add-code-button').addEventListener('click', async () => {
    const button = $('add-code-button');
    const fields = {
        codigo_servico: $('new-service-code').value.trim(),
        codigo_fornecedor: $('new-supplier').value.trim(),
        descricao_item: $('search-input').value.trim(),
        grupo_servico: $('new-group').value.trim(),
        unidade_medida: $('campoMedida').value.trim(), unidade_descricao: ''
    };
    if (!fields.descricao_item) return showNotice('Preencha a descrição antes de cadastrar.', 'error');
    if (!confirm('Confirmar novo serviço? Verifique os similares antes de continuar.')) return;
    const serialized = JSON.stringify(fields);
    if (saveRequestBody !== serialized) { saveRequestId = crypto.randomUUID(); saveRequestBody = serialized; }
    button.disabled = true;
    try {
        const saved = await apiJson('/api/catalog/services', {method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({...fields, request_id: saveRequestId})});
        showNotice(`Serviço ${saved.codigo_servico} salvo no banco. O Excel atualizado está disponível em Exportar catálogo.`, 'success');
        await refreshStatus();
    } catch (error) { showNotice(error.message, 'error'); }
    finally { button.disabled = false; }
});
refreshStatus();
