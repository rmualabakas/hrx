/* HRX — coque : repli de la sidebar, tiroir mobile, dossiers de menu, lien actif. */
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
        if (read(KEY_COLLAPSED) === '1' && !isMobile()) body.classList.add('hx-sidebar-collapsed');
    }

    function initFolders() {
        var open = {};
        try { open = JSON.parse(read(KEY_OPEN_FOLDERS) || '{}'); } catch (e) { open = {}; }

        var folders = document.querySelectorAll('.hx-nav__folder');
        Array.prototype.forEach.call(folders, function (folder) {
            var id = folder.getAttribute('data-folder');
            var trigger = folder.querySelector(':scope > .hx-nav__link');
            if (open[id]) folder.classList.add('is-open');
            if (!trigger) return;
            trigger.addEventListener('click', function (event) {
                event.preventDefault();
                var nowOpen = folder.classList.toggle('is-open');
                trigger.setAttribute('aria-expanded', nowOpen ? 'true' : 'false');
                open[id] = nowOpen;
                write(KEY_OPEN_FOLDERS, JSON.stringify(open));
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
                while (folder) { folder.classList.add('is-open'); folder = folder.parentElement.closest('.hx-nav__folder'); }
            }
        });
    }

    restoreCollapsed();
    document.addEventListener('DOMContentLoaded', function () {
        initFolders();
        markActive();
        var scrim = document.querySelector('.hx-scrim');
        if (scrim) scrim.addEventListener('click', window.hrxCloseSidebar);
        window.requestAnimationFrame(function () { document.documentElement.classList.remove('hx-preload'); });
    });
}(window, document));
