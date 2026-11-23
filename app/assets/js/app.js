(() => {
    'use strict';

    const $ = (id) => document.getElementById(id);
    const MODES = {
        minimal: { name: 'Minimal', need: 'minimalNeed' },
        full: { name: 'Full', need: 'fullNeed' }
    };
    const THEMES = {
        midnight: {
            background: '#0b0f14',
            foreground: '#e5e7eb',
            cursor: '#22d3ee',
            cursorAccent: '#0b0f14',
            selectionBackground: 'rgba(124, 92, 255, 0.35)',
            black: '#1e293b',
            red: '#fb7185',
            green: '#34d399',
            yellow: '#fbbf24',
            blue: '#60a5fa',
            magenta: '#c084fc',
            cyan: '#22d3ee',
            white: '#e2e8f0',
            brightBlack: '#475569',
            brightRed: '#fda4af',
            brightGreen: '#6ee7b7',
            brightYellow: '#fde68a',
            brightBlue: '#93c5fd',
            brightMagenta: '#d8b4fe',
            brightCyan: '#67e8f9',
            brightWhite: '#f8fafc'
        },
        kali: {
            background: '#08111f',
            foreground: '#dbeafe',
            cursor: '#60a5fa',
            cursorAccent: '#08111f',
            selectionBackground: 'rgba(96, 165, 250, 0.30)',
            black: '#0f172a',
            red: '#f87171',
            green: '#4ade80',
            yellow: '#facc15',
            blue: '#60a5fa',
            magenta: '#a78bfa',
            cyan: '#38bdf8',
            white: '#e0f2fe',
            brightBlack: '#334155',
            brightRed: '#fca5a5',
            brightGreen: '#86efac',
            brightYellow: '#fde047',
            brightBlue: '#93c5fd',
            brightMagenta: '#c4b5fd',
            brightCyan: '#7dd3fc',
            brightWhite: '#f8fafc'
        }
    };
    const KEY_SEQUENCES = {
        esc: '\x1b',
        tab: '\t',
        cc: '\x03',
        cd: '\x04',
        cl: '\x0c',
        up: '\x1b[A',
        down: '\x1b[B',
        right: '\x1b[C',
        left: '\x1b[D',
        pipe: '|',
        tilde: '~',
        slash: '/',
        dash: '-'
    };

    let state = null;
    let selectedMode = 'minimal';
    let prefs = { fontSize: 13, theme: 'midnight' };
    let term = null;
    let fitAddon = null;
    let terminalStarted = false;
    let terminalStarting = false;
    let sessionFinished = false;
    let settingsOpen = false;
    let ctrlArmed = false;
    let toastTimer = null;
    let fitTimer = null;
    let refreshTimer = null;
    let lastBridgeError = '';

    function nativeCall(name) {
        const api = window.Kalindroid;
        if (!api || typeof api[name] !== 'function') {
            throw new Error('El puente nativo no está disponible');
        }
        return api[name].apply(api, Array.prototype.slice.call(arguments, 1));
    }

    function parse(value) {
        try {
            return JSON.parse(value || '{}');
        } catch (e) {
            return {};
        }
    }

    function formatBytes(bytes) {
        const n = Number(bytes) || 0;
        if (n <= 0) return '—';
        const gb = n / 1073741824;
        if (gb >= 1) return (gb >= 10 ? gb.toFixed(0) : gb.toFixed(1)) + ' GB';
        return Math.max(1, Math.round(n / 1048576)) + ' MB';
    }

    function modeName(mode) {
        return MODES[mode] ? MODES[mode].name : mode;
    }

    function themeFor(name) {
        return THEMES[name] || THEMES.midnight;
    }

    function showToast(message) {
        const toast = $('toast');
        if (!toast) return;
        toast.textContent = message;
        toast.classList.add('show');
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => toast.classList.remove('show'), 2800);
    }

    function showNotice(message, kind) {
        const notice = $('setupNotice');
        if (!notice) return;
        if (!message) {
            notice.textContent = '';
            notice.classList.add('hidden');
            notice.classList.remove('warn', 'ok');
            return;
        }
        notice.textContent = message;
        notice.classList.remove('hidden', 'warn', 'ok');
        if (kind === 'warn' || kind === 'ok') notice.classList.add(kind);
    }

    function loadPrefs() {
        try {
            const p = parse(nativeCall('prefs'));
            if (p && Number.isFinite(Number(p.fontSize))) {
                prefs.fontSize = Math.max(10, Math.min(22, Number(p.fontSize)));
            }
            if (p && THEMES[p.theme]) prefs.theme = p.theme;
        } catch (e) {
            lastBridgeError = e.message || '';
        }
        const range = $('fontRange');
        const value = $('fontValue');
        if (range) range.value = String(prefs.fontSize);
        if (value) value.textContent = String(prefs.fontSize);
        markTheme(prefs.theme);
    }

    function markTheme(name) {
        document.querySelectorAll('.theme-swatch').forEach((el) => {
            el.classList.toggle('selected', el.dataset.theme === name);
        });
    }

    function applyTheme(name) {
        if (!THEMES[name]) name = 'midnight';
        prefs.theme = name;
        markTheme(name);
        if (term && term.options) term.options.theme = themeFor(name);
        try { nativeCall('setPref', 'theme', name); } catch (e) { /* noop */ }
    }

    function applyFontSize(size) {
        const clamped = Math.max(10, Math.min(22, Number(size) || 13));
        prefs.fontSize = clamped;
        const range = $('fontRange');
        const value = $('fontValue');
        if (range) range.value = String(clamped);
        if (value) value.textContent = String(clamped);
        if (term && term.options) term.options.fontSize = clamped;
        try { nativeCall('setPref', 'font_size', String(clamped)); } catch (e) { /* noop */ }
        scheduleFit();
    }

    function showSetupScreen() {
        const setup = $('setup');
        const terminal = $('terminal');
        if (setup) setup.classList.remove('hidden');
        if (terminal) terminal.classList.add('hidden');
    }

    function showTerminalScreen() {
        const setup = $('setup');
        const terminal = $('terminal');
        if (terminal) terminal.classList.remove('hidden');
        if (setup) setup.classList.add('hidden');
        setTimeout(scheduleFit, 30);
    }

    function showSessionEnded(message) {
        const box = $('sessionEnded');
        const sub = box ? box.querySelector('.ended-sub') : null;
        if (sub) {
            sub.textContent = message || 'Kali se cerró. Puedes iniciar una nueva sesión.';
        }
        if (box) box.classList.remove('hidden');
    }

    function hideSessionEnded() {
        const box = $('sessionEnded');
        if (box) box.classList.add('hidden');
    }

    function ensureTerminal() {
        if (term) return true;
        if (!window.Terminal) {
            showToast('No se pudo cargar el motor de terminal');
            return false;
        }
        term = new window.Terminal({
            fontFamily: '"JetBrains Mono", ui-monospace, monospace',
            fontSize: prefs.fontSize,
            fontWeight: '400',
            lineHeight: 1.25,
            letterSpacing: 0,
            cursorBlink: true,
            cursorStyle: 'bar',
            scrollback: 8000,
            scrollOnUserInput: true,
            drawBoldTextInBrightColors: true,
            theme: themeFor(prefs.theme),
            allowProposedApi: true
        });
        if (window.FitAddon && window.FitAddon.FitAddon) {
            fitAddon = new window.FitAddon.FitAddon();
            term.loadAddon(fitAddon);
        }
        term.open($('terminalHost'));
        const input = document.querySelector('.xterm-helper-textarea');
        if (input) {
            input.setAttribute('autocapitalize', 'none');
            input.setAttribute('autocorrect', 'off');
            input.setAttribute('autocomplete', 'off');
            input.setAttribute('spellcheck', 'false');
            input.setAttribute('inputmode', 'text');
        }
        term.onData(onTermData);
        term.onResize((size) => {
            if (terminalStarted) {
                try { nativeCall('resize', size.cols, size.rows); } catch (e) { /* noop */ }
            }
        });
        if (document.fonts && document.fonts.ready) {
            document.fonts.ready.then(() => {
                if (!term) return;
                term.options.fontFamily = '"JetBrains Mono", ui-monospace, monospace';
                scheduleFit();
            });
        }
        return true;
    }

    function scheduleFit() {
        clearTimeout(fitTimer);
        fitTimer = setTimeout(() => {
            if (!fitAddon || !term) return;
            const terminal = $('terminal');
            if (!terminal || terminal.classList.contains('hidden')) return;
            try {
                fitAddon.fit();
            } catch (e) { /* noop */ }
        }, 70);
    }

    function onTermData(data) {
        if (ctrlArmed && data.length === 1) {
            const code = data.toUpperCase().charCodeAt(0);
            if (code >= 64 && code <= 95) {
                data = String.fromCharCode(code - 64);
            }
            ctrlArmed = false;
            setCtrlVisual(false);
        }
        try { nativeCall('input', data); } catch (e) { /* noop */ }
    }

    function setCtrlVisual(active) {
        const btn = $('ctrlKey');
        if (btn) btn.classList.toggle('active', active);
    }

    function startTerminal() {
        if (terminalStarted || terminalStarting) return;
        terminalStarting = true;
        sessionFinished = false;
        hideSessionEnded();
        showTerminalScreen();
        if (!ensureTerminal()) {
            terminalStarting = false;
            return;
        }
        requestAnimationFrame(() => {
            try { fitAddon.fit(); } catch (e) { /* noop */ }
            let result;
            try {
                result = parse(nativeCall('startTerminal', term.cols, term.rows));
            } catch (e) {
                result = { ok: false, error: e.message };
            }
            terminalStarting = false;
            if (result.ok) {
                terminalStarted = true;
                sessionFinished = false;
                hideSessionEnded();
                term.focus();
            } else {
                terminalStarted = false;
                sessionFinished = true;
                showSessionEnded(result.error || 'No se pudo iniciar Kali.');
            }
        });
    }

    function restartSession() {
        if (!ensureTerminal()) return;
        sessionFinished = false;
        hideSessionEnded();
        showTerminalScreen();
        try { fitAddon.fit(); } catch (e) { /* noop */ }
        try { term.reset(); } catch (e) { /* noop */ }
        let result;
        try {
            result = parse(nativeCall('restartTerminal', term.cols, term.rows));
        } catch (e) {
            result = { ok: false, error: e.message };
        }
        if (result.ok) {
            terminalStarted = true;
            sessionFinished = false;
            hideSessionEnded();
            term.focus();
        } else {
            terminalStarted = false;
            sessionFinished = true;
            showSessionEnded(result.error || 'No se pudo reiniciar Kali.');
        }
    }

    function updateSpace(s) {
        const free = Number(s.freeBytes) || 0;
        const el = $('freeSpace');
        if (el) el.textContent = formatBytes(free) + ' libres';
        const storage = $('sheetStorage');
        if (storage) {
            const installed = s.installed ? ' · Kali ' + modeName(s.mode) : '';
            storage.textContent = formatBytes(free) + ' libres' + installed;
        }
    }

    function updateModeCards() {
        document.querySelectorAll('.mode-card').forEach((card) => {
            card.classList.toggle('selected', card.dataset.mode === selectedMode);
        });
    }

    function updateInstallButton(s, busy, insufficient, need) {
        const btn = $('installBtn');
        const label = $('installLabel');
        if (!btn || !label) return;
        const d = s.download || {};
        let action = 'install';
        let text = 'Instalar ' + modeName(selectedMode);
        let targetMode = selectedMode;

        if (busy) {
            text = s.extracting ? 'Extrayendo Kali…' : 'Descargando Kali…';
        } else if (s.error && s.archive && selectedMode === s.archive) {
            action = 'retry';
            targetMode = s.archive;
            text = 'Reintentar ' + modeName(s.archive);
        } else if (s.error || d.status === 'failed' || d.status === 'missing') {
            text = 'Reintentar descarga';
        }

        if (!busy && insufficient) {
            text = 'Libre ' + formatBytes(need - (Number(s.freeBytes) || 0));
        }

        btn.dataset.action = action;
        btn.dataset.mode = targetMode;
        btn.disabled = busy || insufficient;
        label.textContent = text;
    }

    function updateDownloadPanel(s) {
        const d = s.download || {};
        const panel = $('downloadPanel');
        const title = $('downloadTitle');
        const sub = $('downloadSub');
        const pctText = $('downloadPct');
        const bar = $('progressBar');
        const cancel = $('cancelBtn');
        if (!panel) return;

        let visible = false;
        let titleText = '';
        let subText = '';
        let pct = 0;
        let downloading = false;

        if (s.extracting) {
            visible = true;
            titleText = 'Extrayendo Kali ' + modeName(d.mode || selectedMode);
            subText = 'Descomprimiendo el sistema. No cierres la app.';
            pct = Number(s.extractProgress) || 0;
        } else if (d.status === 'pending' || d.status === 'running' || d.status === 'paused') {
            visible = true;
            downloading = true;
            titleText = 'Descargando Kali ' + modeName(d.mode || selectedMode);
            const downloaded = Number(d.downloaded) || 0;
            const total = Number(d.total) || 0;
            pct = total > 0 ? Math.min(100, (downloaded * 100 / total)) : 0;
            if (d.status === 'paused') {
                subText = d.reason || 'Descarga en pausa';
            } else if (total > 0) {
                subText = formatBytes(downloaded) + ' de ' + formatBytes(total);
            } else {
                subText = 'Conectando con el servidor…';
            }
        } else if (d.status === 'successful' && !s.installed) {
            visible = true;
            titleText = 'Preparando extracción…';
            subText = 'Iniciando el instalador. No cierres la app.';
            pct = 100;
        }

        panel.classList.toggle('hidden', !visible);
        if (!visible) return;
        if (title) title.textContent = titleText;
        if (sub) sub.textContent = subText;
        if (pctText) pctText.textContent = Math.round(pct) + '%';
        if (bar) bar.style.width = Math.max(pct > 0 ? pct : 2, 2) + '%';
        if (cancel) cancel.classList.toggle('hidden', !downloading);
    }

    function updateNotice(s, busy, insufficient, need, needMode) {
        const d = s.download || {};
        if (busy) {
            showNotice('');
            return;
        }
        if (s.error) {
            let message = String(s.error);
            if (s.archive && selectedMode === s.archive) {
                message += ' La imagen descargada se conserva.';
            }
            showNotice(message);
            return;
        }
        if (d.status === 'failed' || d.status === 'missing') {
            showNotice('La descarga falló: ' + (d.reason || 'error desconocido'));
            return;
        }
        if (insufficient) {
            const free = Number(s.freeBytes) || 0;
            showNotice('Kali ' + modeName(needMode) + ' necesita ' + formatBytes(need)
                + ' libres. Tienes ' + formatBytes(free) + '.', 'warn');
            return;
        }
        showNotice('');
    }

    function refresh() {
        let s;
        try {
            s = parse(nativeCall('status'));
        } catch (e) {
            if (e.message !== lastBridgeError) {
                lastBridgeError = e.message;
                showToast(e.message);
            }
            return;
        }
        if (!s || typeof s.installed === 'undefined') return;
        state = s;
        lastBridgeError = '';

        updateSpace(s);

        if (s.installed) {
            showTerminalScreen();
            const badge = $('modeBadge');
            if (badge) badge.textContent = s.mode || 'kali';
            if (!terminalStarted && !terminalStarting && !sessionFinished) startTerminal();
            return;
        }

        if (terminalStarted || terminalStarting) {
            terminalStarted = false;
            terminalStarting = false;
        }
        showSetupScreen();

        const d = s.download || {};
        const downloadBusy = d.status === 'pending' || d.status === 'running' || d.status === 'paused';
        const busy = downloadBusy || s.extracting;
        const needMode = (!busy && s.error && s.archive && selectedMode === s.archive)
            ? s.archive : selectedMode;
        const need = Number(s[needMode === 'full' ? 'fullNeed' : 'minimalNeed']) || 0;
        const free = Number(s.freeBytes) || 0;
        const insufficient = free > 0 && free < need;

        updateModeCards();
        updateInstallButton(s, busy, insufficient, need);
        updateDownloadPanel(s);
        updateNotice(s, busy, insufficient, need, needMode);
    }

    function beginInstall() {
        const btn = $('installBtn');
        if (!btn || btn.disabled) return;
        const action = btn.dataset.action || 'install';
        const mode = btn.dataset.mode || selectedMode;
        let result;
        try {
            result = parse(action === 'retry'
                ? nativeCall('retryExtract', mode)
                : nativeCall('install', selectedMode));
        } catch (e) {
            result = { ok: false, error: e.message };
        }
        if (!result.ok) {
            showToast(result.error || 'No se pudo iniciar la instalación');
        } else {
            showToast(action === 'retry' ? 'Reintentando extracción' : 'Descarga iniciada');
        }
        refresh();
    }

    function cancelInstall() {
        try { nativeCall('cancelInstall'); } catch (e) { /* noop */ }
        showToast('Instalación cancelada');
        refresh();
    }

    function resetInstall() {
        if (!window.confirm('Se eliminará Kali y todos sus datos. ¿Continuar?')) return;
        try { nativeCall('reset'); } catch (e) { /* noop */ }
        if (term) {
            try { term.reset(); } catch (e) { /* noop */ }
        }
        terminalStarted = false;
        terminalStarting = false;
        sessionFinished = false;
        hideSessionEnded();
        closeSettings();
        showSetupScreen();
        showToast('Kali desinstalado');
        setTimeout(refresh, 120);
    }

    function openSettings() {
        settingsOpen = true;
        const sheet = $('settingsSheet');
        if (sheet) sheet.classList.remove('hidden');
        if (state) updateSpace(state);
    }

    function closeSettings() {
        settingsOpen = false;
        const sheet = $('settingsSheet');
        if (sheet) sheet.classList.add('hidden');
    }

    function sendKey(key) {
        if (!KEY_SEQUENCES[key]) return;
        try { nativeCall('input', KEY_SEQUENCES[key]); } catch (e) { /* noop */ }
        if (term) term.focus();
    }

    function pasteClipboard() {
        let text = '';
        try { text = nativeCall('clipboard') || ''; } catch (e) { /* noop */ }
        if (!text) {
            showToast('El portapapeles está vacío');
            return;
        }
        try { nativeCall('input', text); } catch (e) { /* noop */ }
        if (term) term.focus();
        showToast('Pegado');
    }

    function bindEvents() {
        document.querySelectorAll('.mode-card').forEach((card) => {
            card.addEventListener('click', () => {
                if (!state || state.installed || state.extracting) return;
                const d = state.download || {};
                if (d.status === 'pending' || d.status === 'running' || d.status === 'paused') return;
                selectedMode = card.dataset.mode || 'minimal';
                updateModeCards();
                refresh();
            });
        });

        const installBtn = $('installBtn');
        if (installBtn) installBtn.addEventListener('click', beginInstall);

        const cancelBtn = $('cancelBtn');
        if (cancelBtn) cancelBtn.addEventListener('click', cancelInstall);

        const settingsBtn = $('btnSettings');
        if (settingsBtn) settingsBtn.addEventListener('click', openSettings);

        const closeBtn = $('closeSettings');
        if (closeBtn) closeBtn.addEventListener('click', closeSettings);

        const sheet = $('settingsSheet');
        if (sheet) {
            sheet.addEventListener('click', (event) => {
                if (event.target === sheet) closeSettings();
            });
        }

        const pasteBtn = $('btnPaste');
        if (pasteBtn) pasteBtn.addEventListener('click', pasteClipboard);

        const restartBtn = $('restartBtn');
        if (restartBtn) restartBtn.addEventListener('click', restartSession);

        const sessionBtn = $('btnSession');
        if (sessionBtn) {
            sessionBtn.addEventListener('click', () => {
                if (!window.confirm('¿Reiniciar la sesión de Kali?')) return;
                restartSession();
            });
        }

        const resetBtn = $('resetBtn');
        if (resetBtn) resetBtn.addEventListener('click', resetInstall);

        const exitBtn = $('exitBtn');
        if (exitBtn) {
            exitBtn.addEventListener('click', () => {
                try { nativeCall('exitApp'); } catch (e) { /* noop */ }
            });
        }

        const fontRange = $('fontRange');
        if (fontRange) {
            fontRange.addEventListener('input', (event) => {
                applyFontSize(event.target.value);
            });
        }

        document.querySelectorAll('.theme-swatch').forEach((swatch) => {
            swatch.addEventListener('click', () => applyTheme(swatch.dataset.theme));
        });

        document.querySelectorAll('.key[data-send]').forEach((key) => {
            key.addEventListener('click', () => {
                const send = key.dataset.send;
                if (send === 'ctrl') {
                    ctrlArmed = !ctrlArmed;
                    setCtrlVisual(ctrlArmed);
                    if (term) term.focus();
                    return;
                }
                sendKey(send);
            });
        });

        window.addEventListener('resize', scheduleFit);
        if (window.visualViewport) {
            window.visualViewport.addEventListener('resize', scheduleFit);
        }
        document.addEventListener('visibilitychange', () => {
            if (!document.hidden) refresh();
        });
    }

    function init() {
        loadPrefs();
        bindEvents();
        refresh();
        clearInterval(refreshTimer);
        refreshTimer = setInterval(refresh, 1000);
    }

    window.kalindroidData = (data) => {
        if (term && typeof data === 'string') term.write(data);
    };

    window.kalindroidExit = () => {
        terminalStarted = false;
        terminalStarting = false;
        sessionFinished = true;
        showSessionEnded();
        if (settingsOpen) closeSettings();
    };

    window.kalindroidBack = () => {
        if (settingsOpen) {
            closeSettings();
            return true;
        }
        return false;
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
