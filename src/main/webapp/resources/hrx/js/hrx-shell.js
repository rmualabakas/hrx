/* HRX — coque : repli de la sidebar, tiroir mobile, dossiers de menu, lien actif,
   panneau flottant des sous-menus quand la sidebar est repliée. */
(function (window, document) {
    'use strict';

    var body = document.body;
    var KEY_COLLAPSED = 'hrx.sidebar.collapsed';
    var KEY_OPEN_FOLDERS = 'hrx.sidebar.folders';

    function read(key) { try { return window.localStorage.getItem(key); } catch (e) { return null; } }
    function write(key, value) { try { window.localStorage.setItem(key, value); } catch (e) { /* ignore */ } }

    function isMobile() { return window.matchMedia('(max-width: 1024px)').matches; }

    window.hrxToggleSidebar = function () {
        if (isMobile()) {
            body.classList.toggle('hx-sidebar-open');
        } else {
            var collapsed = body.classList.toggle('hx-sidebar-collapsed');
            write(KEY_COLLAPSED, collapsed ? '1' : '0');
        }
    };
    window.hrxCloseSidebar = function () { body.classList.remove('hx-sidebar-open'); };

    function restoreCollapsed() {
        // Menu réduit par défaut ; déplié seulement si l'utilisateur l'a choisi (préférence mémorisée).
        if (read(KEY_COLLAPSED) !== '0' && !isMobile()) body.classList.add('hx-sidebar-collapsed');
    }

    /* Les dossiers sont repliés par défaut : seul celui qui contient la page en cours s'ouvre (markActive).
       L'ouverture manuelle ne vaut que pour la page affichée et n'est plus mémorisée. */
    function initFolders() {
        try { window.localStorage.removeItem(KEY_OPEN_FOLDERS); } catch (e) { /* ignore */ }
        var folders = document.querySelectorAll('.hx-nav__folder');
        Array.prototype.forEach.call(folders, function (folder) {
            var trigger = folder.querySelector(':scope > .hx-nav__link');
            if (!trigger) return;
            trigger.setAttribute('aria-expanded', 'false');
            trigger.addEventListener('click', function (event) {
                event.preventDefault();
                if (isCollapsedDesktop()) return; // géré par le panneau flottant
                var nowOpen = folder.classList.toggle('is-open');
                trigger.setAttribute('aria-expanded', nowOpen ? 'true' : 'false');
            });
        });
    }

    function markActive() {
        var path = window.location.pathname.replace(/\/+$/, '').replace(/\.xhtml$/, '');
        var links = document.querySelectorAll('.hx-nav__link[href]');
        Array.prototype.forEach.call(links, function (link) {
            var href = (link.getAttribute('href') || '').split('?')[0].replace(/\/+$/, '').replace(/\.xhtml$/, '');
            if (href && href === path) {
                link.classList.add('is-active');
                link.setAttribute('aria-current', 'page');
                var folder = link.closest('.hx-nav__folder');
                while (folder) {
                    folder.classList.add('is-open');
                    var t = folder.querySelector(':scope > .hx-nav__link');
                    if (t) t.setAttribute('aria-expanded', 'true');
                    folder = folder.parentElement.closest('.hx-nav__folder');
                }
            }
        });
    }

    /* ---------- Sidebar repliée : panneau flottant (sous-menus et libellés) ---------- */
    function isCollapsedDesktop() { return body.classList.contains('hx-sidebar-collapsed') && !isMobile(); }

    var flyout, flyoutOwner = null, hideTimer = null, pinned = false;

    function ensureFlyout() {
        if (flyout) return flyout;
        flyout = document.createElement('div');
        flyout.className = 'hx-flyout';
        flyout.setAttribute('role', 'menu');
        flyout.hidden = true;
        flyout.addEventListener('mouseenter', function () { window.clearTimeout(hideTimer); });
        flyout.addEventListener('mouseleave', scheduleHide);
        flyout.addEventListener('keydown', function (ev) {
            if (ev.key === 'Escape') { var o = flyoutOwner; hideFlyout(); if (o) o.focus(); }
        });
        body.appendChild(flyout);
        return flyout;
    }

    function labelOf(link) {
        var t = link.querySelector('.hx-nav__text');
        return t ? t.textContent.trim() : '';
    }

    function showFlyout(trigger, pin) {
        if (!isCollapsedDesktop()) return;
        var el = ensureFlyout();
        window.clearTimeout(hideTimer);
        if (flyoutOwner && flyoutOwner !== trigger) flyoutOwner.setAttribute('aria-expanded', 'false');
        flyoutOwner = trigger;
        pinned = !!pin;

        var folder = trigger.closest('.hx-nav__folder');
        var submenu = folder && folder.querySelector(':scope > .hx-nav__submenu');
        el.innerHTML = '';
        var title = document.createElement('div');
        title.className = 'hx-flyout__title';
        title.textContent = labelOf(trigger);
        el.appendChild(title);
        if (submenu && trigger.tagName === 'BUTTON') {
            var list = submenu.cloneNode(true);
            list.className = 'hx-flyout__list';
            Array.prototype.forEach.call(list.querySelectorAll('.hx-nav__submenu'), function (u) { u.className = 'hx-flyout__list hx-flyout__list--nested'; });
            Array.prototype.forEach.call(list.querySelectorAll('a'), function (a) { a.setAttribute('role', 'menuitem'); });
            Array.prototype.forEach.call(list.querySelectorAll('button.hx-nav__link'), function (b) { b.disabled = true; b.classList.add('hx-flyout__group'); });
            el.appendChild(list);
            el.classList.remove('hx-flyout--label');
            trigger.setAttribute('aria-expanded', 'true');
        } else {
            el.classList.add('hx-flyout--label');
        }

        el.hidden = false;
        var r = trigger.getBoundingClientRect();
        var right = body.classList.contains('hrx-menu-right');
        var sidebar = document.querySelector('.hx-sidebar').getBoundingClientRect();
        el.style.left = right ? '' : (sidebar.right + 8) + 'px';
        el.style.right = right ? (window.innerWidth - sidebar.left + 8) + 'px' : '';
        var top = r.top - (el.classList.contains('hx-flyout--label') ? (el.offsetHeight - r.height) / 2 : 6);
        top = Math.max(8, Math.min(top, window.innerHeight - el.offsetHeight - 8));
        el.style.top = top + 'px';
    }

    function hideFlyout() {
        window.clearTimeout(hideTimer);
        pinned = false;
        if (flyoutOwner) flyoutOwner.setAttribute('aria-expanded', 'false');
        flyoutOwner = null;
        if (flyout) flyout.hidden = true;
    }
    function scheduleHide() {
        if (pinned) return;
        window.clearTimeout(hideTimer);
        hideTimer = window.setTimeout(hideFlyout, 180);
    }

    function initFlyout() {
        var triggers = document.querySelectorAll('.hx-nav > .hx-nav__module > ul > li > .hx-nav__link');
        Array.prototype.forEach.call(triggers, function (t) {
            t.addEventListener('mouseenter', function () { if (!pinned || flyoutOwner !== t) showFlyout(t, false); });
            t.addEventListener('mouseleave', scheduleHide);
            t.addEventListener('focus', function () { if (isCollapsedDesktop() && t.tagName !== 'BUTTON') showFlyout(t, false); });
            t.addEventListener('blur', scheduleHide);
            if (t.tagName === 'BUTTON') {
                t.addEventListener('click', function () {
                    if (!isCollapsedDesktop()) return;
                    if (pinned && flyoutOwner === t) { hideFlyout(); return; }
                    showFlyout(t, true);
                    var first = flyout.querySelector('a');
                    if (first) first.focus();
                });
            }
        });
        document.addEventListener('click', function (ev) {
            if (!flyout || flyout.hidden) return;
            if (flyout.contains(ev.target) || (flyoutOwner && flyoutOwner.contains(ev.target))) return;
            hideFlyout();
        });
        document.addEventListener('keydown', function (ev) { if (ev.key === 'Escape') hideFlyout(); });
        var scroller = document.querySelector('.hx-sidebar__scroll');
        if (scroller) scroller.addEventListener('scroll', hideFlyout, { passive: true });
        window.addEventListener('resize', hideFlyout);
    }

    var baseToggle = window.hrxToggleSidebar;
    window.hrxToggleSidebar = function () { hideFlyout(); baseToggle(); };

    restoreCollapsed();
    document.addEventListener('DOMContentLoaded', function () {
        initFolders();
        markActive();
        initFlyout();
        var scrim = document.querySelector('.hx-scrim');
        if (scrim) scrim.addEventListener('click', window.hrxCloseSidebar);
        window.requestAnimationFrame(function () { document.documentElement.classList.remove('hx-preload'); });
    });
}(window, document));

/*
 * Vrai si un menu, une liste déroulante ou une fenêtre PrimeFaces est réellement affiché.
 * PrimeFaces 15 masque ses overlays par animation (display:none) sans remettre « ui-helper-hidden » :
 * on teste donc la visibilité effective, pas la classe. Sert à suspendre un rafraîchissement automatique.
 */
window.hxOverlayOuvert = function () {
    var l = document.querySelectorAll('.ui-selectonemenu-panel, .ui-menu.ui-menu-dynamic, .ui-menu-overlay, .ui-dialog, .ui-confirm-dialog');
    for (var i = 0; i < l.length; i++) {
        var e = l[i];
        if (e.getClientRects().length > 0 && window.getComputedStyle(e).visibility !== 'hidden') return true;
    }
    return false;
};

/**
 * Copie la valeur d'un champ dans le presse-papiers, avec un retour visuel sur le bouton (icône ✓ et infobulle « Copié »).
 * L'API Clipboard exige un contexte sécurisé (HTTPS ou localhost) : sinon, repli sur la sélection + execCommand('copy').
 */
window.hxCopier = function (champ, bouton) {
    if (!champ) return;
    var texte = champ.value || '';
    function retour(ok) {
        if (!bouton) return;
        var icone = bouton.querySelector('.ui-icon, .pi');
        var titre = bouton.getAttribute('title');
        if (icone) { icone.classList.remove('pi-copy'); icone.classList.add(ok ? 'pi-check' : 'pi-times'); }
        bouton.setAttribute('title', ok ? 'Copié' : 'Copie impossible : sélectionnez le texte et copiez-le');
        bouton.classList.add(ok ? 'is-copied' : 'is-copy-ko');
        setTimeout(function () {
            if (icone) { icone.classList.remove('pi-check', 'pi-times'); icone.classList.add('pi-copy'); }
            bouton.setAttribute('title', titre);
            bouton.classList.remove('is-copied', 'is-copy-ko');
        }, 1600);
    }
    function repli() {
        try { champ.focus(); champ.select(); retour(document.execCommand('copy')); } catch (e) { retour(false); }
    }
    if (navigator.clipboard && window.isSecureContext) navigator.clipboard.writeText(texte).then(function () { retour(true); }, repli);
    else repli();
};

/* Fenêtre de confirmation stylée (appelée par cd.mura.hrx.utils.Dialogue). Texte inséré via textContent : aucun HTML interprété. */
window.hxDialogue = function (o) {
    o = o || {};
    var ancien = document.getElementById('hxDialogue');
    if (ancien) ancien.remove();
    var icones = { succes: 'pi-check', info: 'pi-info', alerte: 'pi-exclamation-triangle', erreur: 'pi-times' };
    var eyebrows = { succes: 'Opération effectuée', info: 'Information', alerte: 'Attention', erreur: 'Action impossible' };
    var type = icones[o.type] ? o.type : 'succes';
    function el(tag, cls, txt) { var e = document.createElement(tag); if (cls) e.className = cls; if (txt) e.textContent = txt; return e; }

    var voile = el('div', 'hx-dlg-voile');
    voile.id = 'hxDialogue';
    var boite = el('div', 'hx-alerte hx-alerte--' + type + ' hx-dlg-boite');
    boite.setAttribute('role', 'dialog'); boite.setAttribute('aria-modal', 'true');
    var fermer = el('button', 'hx-alerte__x'); fermer.type = 'button'; fermer.setAttribute('aria-label', 'Fermer');
    fermer.appendChild(el('i', 'pi pi-times'));
    var ico = el('div', 'hx-alerte__ico'); ico.appendChild(el('i', 'pi ' + icones[type]));
    boite.appendChild(fermer); boite.appendChild(ico);
    boite.appendChild(el('div', 'hx-alerte__eyebrow', eyebrows[type]));
    var h = el('h3', null, o.titre || ''); h.id = 'hxDialogueTitre'; boite.setAttribute('aria-labelledby', h.id);
    boite.appendChild(h);
    if (o.texte) boite.appendChild(el('p', null, o.texte));
    var actions = el('div', 'hx-alerte__actions');
    var ok = el('button', 'hx-alerte__btn' + (o.lien ? ' hx-alerte__btn--ghost' : ''), o.lien ? 'Fermer' : 'Compris'); ok.type = 'button';
    actions.appendChild(ok);
    if (o.lien) { var a = el('a', 'hx-alerte__btn', (o.lienLibelle || 'Ouvrir') + ' '); a.href = o.lien; a.appendChild(el('i', 'pi pi-arrow-right')); actions.appendChild(a); }
    boite.appendChild(actions);
    voile.appendChild(boite);
    document.body.appendChild(voile);

    function clore() { document.removeEventListener('keydown', clavier); voile.classList.remove('is-ouvert'); setTimeout(function () { voile.remove(); }, 180); }
    function clavier(e) { if (e.key === 'Escape') clore(); }
    fermer.onclick = clore; ok.onclick = clore;
    voile.addEventListener('click', function (e) { if (e.target === voile) clore(); });
    document.addEventListener('keydown', clavier);
    requestAnimationFrame(function () { voile.classList.add('is-ouvert'); ok.focus(); });
};

/* Mémorise l'onglet actif dans l'URL (?onglet=n) pour qu'une actualisation y revienne. */
window.hxOngletUrl = function (index) {
    try {
        var u = new URL(window.location.href);
        if (index > 0) u.searchParams.set('onglet', index); else u.searchParams.delete('onglet');
        window.history.replaceState(window.history.state, '', u.toString());
    } catch (e) { /* navigateur ancien : sans effet */ }
};
