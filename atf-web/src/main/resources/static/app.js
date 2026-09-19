let pollTimer = null;
  let mcpSourceCounter = 0;
  let activeJobId = null;
  let connectionRevision = 0;
  let connectionBusy = false;
  let currentProvider = 'ollama';
  let serverConfig = null;
  const providerDrafts = {};
  const fallbackDefaults = {
    ollama: {baseUrl: 'http://localhost:11434', model: '', timeoutSeconds: 300, temperature: 0.2},
    compatible: {baseUrl: 'http://localhost:8000/v1', model: '', timeoutSeconds: 300, temperature: 0.2},
    openai: {baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini', timeoutSeconds: 180, temperature: 0.2},
    offline: {baseUrl: '', model: '', timeoutSeconds: 300, temperature: 0.2}
  };
  const providerNames = {
    ollama: 'Ollama',
    compatible: 'Совместимый API',
    openai: 'OpenAI',
    offline: 'Без LLM'
  };
  const successfulStatuses = new Set(['GENERATED', 'WRITTEN', 'VALIDATED', 'FIXED_AND_VALIDATED']);

  function updateRunSummary() {
    const projectPath = document.getElementById('projectPath').value.trim();
    const provider = document.getElementById('llm').value;
    const dryRun = document.getElementById('dryRun').checked;
    const validation = document.getElementById('validate').checked;
    const summaryValues = {
      summaryProject: projectPath.replace(/[\\/]+$/, '').split(/[\\/]/).pop() || projectPath || 'Не выбран',
      summaryProvider: providerNames[provider] || provider,
      summaryModel: provider === 'offline' ? 'Детерминированный' : document.getElementById('model').value.trim() || 'Не указана',
      summaryMode: dryRun ? 'Пробный запуск' : 'Запись в проект',
      summaryValidation: validation && !dryRun ? 'После записи' : 'Выключена',
      summaryContext: document.getElementById('context').checked ? 'Включён' : 'Не используется'
    };
    for (const [id, text] of Object.entries(summaryValues)) {
      const element = document.getElementById(id);
      if (element) {
        element.textContent = text;
        element.title = id === 'summaryProject' ? projectPath : text;
      }
    }
    const hint = document.getElementById('dryRunHint');
    if (hint) hint.textContent = dryRun
      ? 'Тесты будут сгенерированы без записи файлов и запуска проверки. Ваш проект останется без изменений.'
      : validation
        ? 'Тесты будут записаны в проект и проверены. Для проверки нужны Docker или Java и Maven/Gradle.'
        : 'Тесты будут записаны в проект. Включите проверку, чтобы запустить их и исправить ошибки автоматически.';
  }

  function setStartButton(running, label) {
    document.getElementById('startBtn').disabled = running;
    const labelElement = document.getElementById('startBtnLabel') || document.getElementById('startBtn');
    labelElement.textContent = label || (running ? 'Генерируем тесты…' : 'Сгенерировать тесты');
    document.getElementById('startBtn').setAttribute('aria-busy', String(running));
  }

  function useDemoProject() {
    providerDrafts[currentProvider] = Object.fromEntries(['baseUrl', 'model', 'timeoutSeconds', 'temperature'].map(id => [id, document.getElementById(id).value]));
    document.getElementById('projectPath').value = 'examples/demo-project';
    document.getElementById('classes').value = '';
    document.getElementById('dryRun').checked = true;
    document.getElementById('validate').checked = false;
    document.getElementById('context').checked = false;
    applyProvider('offline');
    updateRunSummary();
    const generationLink = document.querySelector('.nav-link[href="#generation"]');
    if (generationLink) setActiveNavigation(generationLink);
    showStatus('startStatus', 'Демо-проект выбран. Запустите генерацию, чтобы проверить установку без подключения к модели.');
    document.getElementById('projectPath').focus({preventScroll: true});
    document.getElementById('projectPath').scrollIntoView({behavior: scrollBehavior(), block: 'center'});
  }

  function showHelp() {
    const dialog = document.getElementById('helpDialog');
    if (dialog && !dialog.open) dialog.showModal();
  }

  function closeHelp() {
    document.getElementById('helpDialog')?.close();
  }

  function showResults() {
    const link = document.querySelector('.nav-link[href="#resultsSection"]');
    if (link) setActiveNavigation(link);
    document.getElementById('resultsSection')?.scrollIntoView({behavior: scrollBehavior(), block: 'start'});
  }

  function scrollBehavior() {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth';
  }

  function setActiveNavigation(activeLink) {
    for (const link of document.querySelectorAll('.nav-link')) {
      const active = link === activeLink;
      link.classList.toggle('active', active);
      if (active) link.setAttribute('aria-current', 'location');
      else link.removeAttribute('aria-current');
    }
  }

  function showStatus(id, message, kind = '') {
    const element = document.getElementById(id);
    element.textContent = message;
    element.className = 'notice' + (kind ? ' ' + kind : '');
  }

  function applyProvider(provider, reset = false) {
    currentProvider = provider;
    document.getElementById('llm').value = provider;
    const defaults = serverConfig?.providers?.[provider] || fallbackDefaults[provider];
    const settings = (!reset && providerDrafts[provider]) || defaults;
    for (const name of ['baseUrl', 'model', 'timeoutSeconds', 'temperature']) {
      document.getElementById(name).value = settings[name] ?? fallbackDefaults[provider][name];
    }
    document.getElementById('llmApiKey').value = '';
    document.getElementById('modelOptions').replaceChildren();
    document.getElementById('discoveredModelsField').classList.add('hidden');
    const offline = provider === 'offline';
    document.getElementById('connectionFields').classList.toggle('hidden', offline);
    document.getElementById('baseUrl').readOnly = provider === 'openai';
    document.getElementById('modelsBtn').disabled = offline || connectionBusy;
    document.getElementById('providerHelp').textContent = {
      ollama: 'Модели на вашем компьютере или сервере. Укажите адрес Ollama без /v1 и имя уже загруженной модели.',
      compatible: 'Подключите Qwen, vLLM, LM Studio или LocalAI. Обычно адрес API заканчивается на /v1.',
      openai: 'Генерация через официальный API OpenAI. Укажите ключ и выберите доступную вам модель.',
      offline: 'Быстрая проверка установки без сети и ключей. Создаёт простые шаблонные тесты.'
    }[provider];
    updateKeyHelp();
    connectionRevision++;
    showStatus('connectionStatus', '');
    updateRunSummary();
  }

  function updateKeyHelp() {
    const configured = serverConfig?.providers?.[currentProvider];
    const sameEndpoint = configured?.baseUrl?.replace(/\/+$/, '') === document.getElementById('baseUrl').value.trim().replace(/\/+$/, '');
    document.getElementById('keyHelp').textContent = configured?.apiKeyConfigured && sameEndpoint
      ? 'На сервере уже настроен ключ для этого адреса. Оставьте поле пустым, чтобы использовать его, или введите другой ключ.'
      : currentProvider === 'ollama' ? 'Для локального Ollama ключ обычно не нужен.'
      : 'Введите ключ, если сервер требует авторизацию. Он не сохраняется в хранилище браузера.';
  }

  function connectionSettings(requireModel = true) {
    const provider = document.getElementById('llm').value;
    if (provider === 'offline') return {provider};
    const baseUrl = document.getElementById('baseUrl').value.trim();
    let parsed;
    try { parsed = new URL(baseUrl); } catch { throw new Error('Укажите полный адрес API модели, начиная с http:// или https://.'); }
    if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password || parsed.search || parsed.hash) {
      throw new Error('Адрес API должен использовать http:// или https:// без пароля, параметров запроса и #. Ключ вводится отдельно.');
    }
    const model = document.getElementById('model').value.trim();
    if (requireModel && !model) throw new Error('Выберите или введите точное имя модели. Кнопка «Найти модели» поможет получить список.');
    for (const id of ['timeoutSeconds', 'temperature']) {
      const input = document.getElementById(id);
      if (!input.value || !input.checkValidity()) {
        input.closest('details').open = true;
        input.reportValidity();
        throw new Error('Проверьте параметры запроса: время ожидания и температуру.');
      }
    }
    return {
      provider, baseUrl, model: model || null,
      apiKey: document.getElementById('llmApiKey').value.trim() || null,
      timeoutSeconds: Number(document.getElementById('timeoutSeconds').value),
      temperature: Number(document.getElementById('temperature').value)
    };
  }

  async function requestJson(path, options = {}, timeoutMillis = 30000) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMillis);
    try {
      const response = await fetch(path, {...options, headers: apiHeaders(Boolean(options.body)), signal: controller.signal});
      const data = await response.json().catch(() => ({}));
      if (!response.ok) {
        const message = response.status === 401 || response.status === 403
          ? 'Нет доступа. Проверьте токен AutoTestForge и разрешённые каталоги проекта.'
          : data.detail || data.message || 'Сервер вернул HTTP ' + response.status;
        throw new Error(String(message).slice(0, 1500));
      }
      return data;
    } catch (error) {
      if (error.name === 'AbortError') throw new Error('Истекло время ожидания ответа. Проверьте доступность сервера; запрос мог продолжить выполняться.');
      if (error instanceof TypeError) throw new Error('Не удалось связаться с AutoTestForge. Проверьте, что приложение запущено и доступно по текущему адресу.');
      throw error;
    } finally {
      clearTimeout(timer);
    }
  }

  async function loadLlmConfig(reset = false) {
    const button = document.getElementById('defaultsBtn');
    button.disabled = true;
    const revision = connectionRevision;
    try {
      const config = await requestJson('/api/llm/config');
      serverConfig = config;
      if (reset || revision === connectionRevision) {
        const provider = reset ? currentProvider : config.defaultProvider;
        applyProvider(fallbackDefaults[provider] ? provider : 'ollama', true);
      }
      updateKeyHelp();
      updateRunSummary();
      showStatus('configStatus', reset ? 'Для выбранного подключения загружены настройки сервера. Введённый ключ очищен.' : 'Настройки сервера загружены. Их можно изменить ниже.');
    } catch (error) {
      showStatus('configStatus', 'Настройки сервера не загружены: ' + error.message + ' После ввода токена нажмите «Настройки сервера».', 'bad');
    } finally {
      button.disabled = false;
    }
  }

  async function checkConnection(action) {
    if (connectionBusy) return;
    const revision = connectionRevision;
    try {
      const settings = connectionSettings(action === 'test');
      connectionBusy = true;
      document.getElementById('modelsBtn').disabled = true;
      document.getElementById('testBtn').disabled = true;
      showStatus('connectionStatus', action === 'models' ? 'Получаем список моделей…' : 'Отправляем короткий запрос модели. Проверка может занять до 60 секунд…');
      const result = await requestJson('/api/llm/' + action,
        {method: 'POST', body: JSON.stringify(settings)}, ((settings.timeoutSeconds || 300) + 15) * 1000);
      if (revision !== connectionRevision) return;
      if (action === 'models') {
        const models = Array.isArray(result.models) ? result.models.filter(model => typeof model === 'string') : [];
        const list = document.getElementById('modelOptions');
        list.replaceChildren();
        const selection = document.getElementById('discoveredModels');
        selection.replaceChildren(new Option('Выберите модель…', ''));
        for (const model of models) {
          const option = document.createElement('option');
          option.value = model;
          list.appendChild(option);
          selection.appendChild(new Option(model, model));
        }
        document.getElementById('discoveredModelsField').classList.toggle('hidden', !models.length);
        if (!document.getElementById('model').value.trim() && models.length === 1) document.getElementById('model').value = models[0];
        showStatus('connectionStatus', models.length
          ? 'Найдено моделей: ' + models.length + '. Выберите имя в поле «Имя модели», затем проверьте подключение.'
          : 'Сервер не вернул модели. Проверьте установку модели или введите её точное имя вручную.', models.length ? 'ok' : '');
      } else {
        showStatus('connectionStatus', (result.success ? 'Подключение работает. ' : 'Проверка не пройдена. ') + (result.message || ''), result.success ? 'ok' : 'bad');
      }
    } catch (error) {
      if (revision === connectionRevision) showStatus('connectionStatus', error.message, 'bad');
    } finally {
      connectionBusy = false;
      document.getElementById('modelsBtn').disabled = currentProvider === 'offline';
      document.getElementById('testBtn').disabled = false;
      updateRunSummary();
    }
  }

  async function start() {
    const btn = document.getElementById('startBtn');
    if (btn.disabled) return;
    setStartButton(true, 'Подготавливаем запуск…');
    let submitted = false;
    showStatus('startStatus', '');
    try {
      const projectPath = document.getElementById('projectPath').value.trim();
      if (!projectPath) {
        document.getElementById('projectPath').reportValidity();
        throw new Error('Укажите путь к Java-проекту.');
      }
      const body = {
        projectPath,
        classes: document.getElementById('classes').value.split(',').map(s => s.trim()).filter(Boolean),
        llm: document.getElementById('llm').value,
        llmConnection: connectionSettings(),
        validate: document.getElementById('validate').checked,
        dryRun: document.getElementById('dryRun').checked,
        context: document.getElementById('context').checked,
        contextSources: document.getElementById('contextSources').value.split(',').map(s => s.trim()).filter(Boolean),
        contextQuery: document.getElementById('contextQuery').value.trim() || null,
        mcpSources: collectMcpSources(),
        contextFiles: await readContextFiles()
      };
      showStatus('startStatus', 'Запускаем генерацию…');
      const job = await requestJson('/api/generation', {method: 'POST', body: JSON.stringify(body)});
      if (!job.id) throw new Error('Сервер не вернул номер задачи. Проверьте состояние приложения.');
      submitted = true;
      activeJobId = job.id;
      document.getElementById('resultEmpty')?.classList.add('hidden');
      document.getElementById('jobCard').classList.remove('hidden');
      document.getElementById('resultBlock').classList.add('hidden');
      document.getElementById('jobId').textContent = 'ID: ' + job.id;
      showStatus('startStatus', body.dryRun ? 'Пробный запуск: файлы проекта не изменяются.' : 'Запущена генерация с записью тестов в проект.');
      render(job);
      showResults();
      poll(job.id);
    } catch (e) {
      showStatus('startStatus', 'Не удалось запустить: ' + e.message, 'bad');
    } finally {
      if (!submitted) setStartButton(false);
    }
  }

  function addMcpSource(initial = {}) {
    const id = ++mcpSourceCounter;
    const source = document.createElement('div');
    source.className = 'mcp-source';
    source.dataset.sourceId = String(id);
    source.innerHTML = `
      <h4>
        <span>MCP источник #${id}</span>
        <button class="danger" type="button" aria-label="Удалить MCP-источник ${id}" onclick="removeMcpSource(${id})">Удалить</button>
      </h4>
      <div class="checks">
        <label><input type="checkbox" class="mcp-enabled" checked> Включен</label>
      </div>
      <div class="row">
        <div>
          <label for="mcp-name-${id}">Имя источника</label>
          <input type="text" id="mcp-name-${id}" class="mcp-name" placeholder="confluence" value="${escapeAttribute(initial.name || '')}">
        </div>
        <div>
          <label for="mcp-command-${id}">Команда MCP-сервера</label>
          <input type="text" id="mcp-command-${id}" class="mcp-command" placeholder="npx" value="${escapeAttribute(initial.command || '')}">
        </div>
      </div>
      <div class="row">
        <div>
          <label for="mcp-args-${id}">Аргументы команды</label>
          <input type="text" id="mcp-args-${id}" class="mcp-args" placeholder="-y @your-org/confluence-mcp-server" value="${escapeAttribute(initial.args || '')}">
          <p class="hint">Пути с пробелами заключайте в кавычки. Также поддерживается JSON-массив строк.</p>
        </div>
        <div>
          <label for="mcp-tool-${id}">Имя MCP-инструмента</label>
          <input type="text" id="mcp-tool-${id}" class="mcp-tool" placeholder="search" value="${escapeAttribute(initial.toolName || '')}">
        </div>
      </div>
      <div class="row">
        <div>
          <label for="mcp-query-argument-${id}">Аргумент запроса</label>
          <input type="text" id="mcp-query-argument-${id}" class="mcp-query-argument" placeholder="query" value="${escapeAttribute(initial.queryArgument || 'query')}">
        </div>
        <div>
          <label for="mcp-max-chars-${id}">Лимит символов</label>
          <input type="text" id="mcp-max-chars-${id}" class="mcp-max-chars" inputmode="numeric" placeholder="8000" value="${escapeAttribute(initial.maxChars || '8000')}">
        </div>
      </div>
      <label for="mcp-query-template-${id}">Шаблон запроса для этого источника</label>
      <textarea id="mcp-query-template-${id}" class="mcp-query-template" placeholder="Find requirements for ${'${fullyQualifiedName}'}. Methods: ${'${methods}'}">${escapeHtml(initial.queryTemplate || '')}</textarea>
    `;
    document.getElementById('mcpSources').appendChild(source);
  }

  function removeMcpSource(id) {
    document.querySelector(`[data-source-id="${id}"]`)?.remove();
  }

  function collectMcpSources() {
    const sources = [...document.querySelectorAll('.mcp-source')]
      .filter(source => source.querySelector('.mcp-enabled').checked)
      .map(source => ({
        name: value(source, '.mcp-name'),
        enabled: source.querySelector('.mcp-enabled').checked,
        command: value(source, '.mcp-command'),
        args: splitArgs(value(source, '.mcp-args')),
        toolName: value(source, '.mcp-tool'),
        queryArgument: value(source, '.mcp-query-argument') || 'query',
        queryTemplate: value(source, '.mcp-query-template'),
        maxChars: Number.parseInt(value(source, '.mcp-max-chars') || '8000', 10)
      }))
      .filter(source => source.enabled && (source.name || source.command || source.toolName));
    for (const source of sources) {
      if (!source.name || !source.command || !source.toolName) {
        throw new Error('Для каждого включённого MCP-источника заполните имя, команду и имя tool либо отключите источник.');
      }
      if (!Number.isInteger(source.maxChars) || source.maxChars < 1) throw new Error('Лимит символов MCP-источника должен быть положительным целым числом.');
    }
    return sources;
  }

  function value(root, selector) {
    return root.querySelector(selector).value.trim();
  }

  function splitArgs(args) {
    if (!args) return [];
    if (args.startsWith('[')) {
      let parsed;
      try { parsed = JSON.parse(args); } catch { throw new Error('Аргументы MCP в формате JSON должны быть массивом строк.'); }
      if (!Array.isArray(parsed) || parsed.some(arg => typeof arg !== 'string')) throw new Error('Аргументы MCP должны быть массивом строк.');
      return parsed;
    }
    const values = [];
    let token = '', quote = null, started = false;
    for (const character of args) {
      if (quote) {
        if (character === quote) quote = null;
        else token += character;
      } else if (character === '"' || character === "'") {
        quote = character;
        started = true;
      } else if (/\s/.test(character)) {
        if (started) values.push(token);
        token = '';
        started = false;
      } else {
        token += character;
        started = true;
      }
    }
    if (quote) throw new Error('Закройте кавычки в аргументах MCP-команды.');
    if (started) values.push(token);
    return values;
  }

  async function readContextFiles() {
    const files = [...document.getElementById('contextFiles').files];
    return Promise.all(files.map(async file => {
      try {
        return {name: file.name, type: file.type || 'text/plain', content: await file.text()};
      } catch {
        throw new Error('Не удалось прочитать файл контекста «' + file.name + '». Выберите файл заново и повторите запуск.');
      }
    }));
  }

  function poll(id) {
    clearTimeout(pollTimer);
    document.getElementById('resumeBtn').classList.add('hidden');
    showStatus('pollStatus', '');
    let failures = 0;
    async function update() {
      try {
        const job = await requestJson('/api/generation/' + encodeURIComponent(id), {}, 10000);
        if (activeJobId !== id) return;
        failures = 0;
        showStatus('pollStatus', '');
        render(job);
        if (job.state !== 'RUNNING') {
          activeJobId = null;
          setStartButton(false, 'Запустить ещё раз');
          return;
        }
      } catch (error) {
        if (activeJobId !== id) return;
        failures++;
        if (failures >= 3) {
          showStatus('pollStatus', 'Не удалось обновить статус: ' + error.message + ' Задача может продолжать работу на сервере. Восстановите соединение и нажмите «Обновить статус задачи», прежде чем запускать её повторно.', 'bad');
          document.getElementById('resumeBtn').classList.remove('hidden');
          setStartButton(false, 'Запустить ещё раз');
          return;
        }
        showStatus('pollStatus', 'Не удалось обновить статус. Повторная попытка ' + failures + '/3…');
      }
      pollTimer = setTimeout(update, failures ? 3000 : 1500);
    }
    update();
  }

  function resumePolling() {
    if (activeJobId) {
      setStartButton(true);
      poll(activeJobId);
    }
  }

  function apiHeaders(json) {
    const headers = {};
    if (json) headers['Content-Type'] = 'application/json';
    const token = document.getElementById('apiToken').value.trim();
    if (token) headers['Authorization'] = 'Bearer ' + token;
    return headers;
  }

  function render(job) {
    const results = Array.isArray(job.results) ? job.results : [];
    const metrics = {
      resultCount: results.length,
      successCount: results.filter(result => successfulStatuses.has(result.status)).length,
      attemptCount: results.reduce((sum, result) => sum + (Number.isFinite(Number(result.llmAttempts)) ? Number(result.llmAttempts) : 0), 0)
    };
    for (const [id, count] of Object.entries(metrics)) {
      const element = document.getElementById(id);
      if (element) element.textContent = String(count);
    }
    setStartButton(job.state === 'RUNNING', job.state === 'RUNNING' ? 'Генерируем тесты…' : 'Запустить ещё раз');
    const state = document.getElementById('jobState');
    state.textContent = statusText(job.state);
    state.className = 'status-' + job.state;

    const events = document.getElementById('events');
    events.textContent = (job.events || []).join('\n') + (job.error ? '\nОШИБКА: ' + job.error : '');
    events.scrollTop = events.scrollHeight;

    if (results.length > 0 || job.state !== 'RUNNING') {
      document.getElementById('resultBlock').classList.remove('hidden');
      const rows = document.getElementById('resultRows');
      rows.innerHTML = '';
      for (const r of results) {
        const ok = successfulStatuses.has(r.status);
        const tr = document.createElement('tr');
        tr.innerHTML =
          '<td class="mono">' + escapeHtml(r.classFqn) + '</td>' +
          '<td class="' + (ok ? 'ok' : 'bad') + '">' + escapeHtml(statusText(r.status)) + '</td>' +
          '<td>' + escapeHtml(r.llmAttempts) + '</td>' +
          '<td class="mono">' + escapeHtml(r.errorMessage || r.writtenPath || '-') + '</td>';
        rows.appendChild(tr);
      }
      document.getElementById('summary').textContent = job.summary || '';
    }
  }

  function escapeHtml(value) {
    const div = document.createElement('div');
    div.textContent = value == null ? '' : value;
    return div.innerHTML;
  }

  function escapeAttribute(value) {
    return escapeHtml(value).replace(/"/g, '&quot;');
  }

  function statusText(status) {
    return {
      RUNNING: 'ВЫПОЛНЯЕТСЯ',
      COMPLETED: 'ЗАВЕРШЕНО',
      FAILED: 'ОШИБКА',
      GENERATED: 'СГЕНЕРИРОВАН',
      WRITTEN: 'ЗАПИСАН',
      VALIDATED: 'ПРОВЕРЕН',
      FIXED_AND_VALIDATED: 'ИСПРАВЛЕН И ПРОВЕРЕН',
      VALIDATION_FAILED: 'ОШИБКА ВАЛИДАЦИИ'
    }[status] || status;
  }

  document.getElementById('llm').addEventListener('change', () => {
    providerDrafts[currentProvider] = Object.fromEntries(['baseUrl', 'model', 'timeoutSeconds', 'temperature'].map(id => [id, document.getElementById(id).value]));
    applyProvider(document.getElementById('llm').value);
  });
  for (const id of ['baseUrl', 'model', 'llmApiKey', 'timeoutSeconds', 'temperature']) {
    document.getElementById(id).addEventListener('input', () => {
      connectionRevision++;
      showStatus('connectionStatus', 'Настройки изменены. Проверьте подключение с новыми параметрами.');
      if (id === 'baseUrl') {
        document.getElementById('modelOptions').replaceChildren();
        document.getElementById('discoveredModelsField').classList.add('hidden');
        updateKeyHelp();
      }
      updateRunSummary();
    });
  }
  document.getElementById('discoveredModels').addEventListener('change', event => {
    if (event.target.value) {
      document.getElementById('model').value = event.target.value;
      document.getElementById('model').dispatchEvent(new Event('input'));
    }
  });
  document.getElementById('context').addEventListener('change', event => {
    document.getElementById('contextDetails').open = event.target.checked;
  });
  for (const id of ['projectPath', 'classes', 'dryRun', 'validate', 'context', 'contextSources', 'contextQuery', 'contextFiles']) {
    document.getElementById(id).addEventListener('input', updateRunSummary);
    document.getElementById(id).addEventListener('change', updateRunSummary);
  }
  for (const link of document.querySelectorAll('a[href^="#"]')) {
    link.addEventListener('click', () => {
      const target = link.getAttribute('href');
      const navigationLink = [...document.querySelectorAll('.nav-link')].find(item => item.getAttribute('href') === target);
      if (navigationLink) setActiveNavigation(navigationLink);
      if (target === '#contextSection' || target === '#contextDetails') document.getElementById('contextDetails').open = true;
    });
  }
  const initialNavigation = document.querySelector('.nav-link.active');
  if (initialNavigation) setActiveNavigation(initialNavigation);
  document.getElementById('jumpToResults')?.addEventListener('click', showResults);
  document.getElementById('helpDialog')?.addEventListener('click', event => {
    if (event.target !== event.currentTarget) return;
    const bounds = event.currentTarget.getBoundingClientRect();
    if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) closeHelp();
  });
  for (const id of ['resultCount', 'successCount', 'attemptCount']) {
    const element = document.getElementById(id);
    if (element) element.textContent = '—';
  }
  applyProvider('ollama');
  loadLlmConfig();
  addMcpSource();
