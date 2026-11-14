/* CVthèque : menus contextuels (clic droit) sur les dossiers, les fichiers et le fond du dossier. */
(function () {
    'use strict';

    var menu = null;

    function racine() { return document.querySelector('.hx-cvt'); }

    function fermer() {
        if (menu) { menu.remove(); menu = null; }
        document.querySelectorAll('.hx-ctx-on').forEach(function (e) { e.classList.remove('hx-ctx-on'); });
    }

    function poser(cible) {
        var champ = document.getElementById('ctxCible');
        if (champ) champ.value = cible;
    }

    function agir(cible, act) {
        poser(cible);
        if (typeof window.hxCvtCtx === 'function') window.hxCvtCtx([{ name: 'act', value: act }]);
    }

    function cliquer(cible, id) {
        poser(cible);
        var b = document.getElementById(id);
        if (b) b.click();
    }

    function entrees(el, cible) {
        var r = racine(), base = r ? r.getAttribute('data-base') || '' : '';
        var archives = r && r.getAttribute('data-archives') === 'true';
        var type = cible.split(':')[0], id = cible.split(':')[1];

        if (type === 'racine') {
            return [
                { label: 'Ouvrir', icon: 'pi-folder-open', run: function () { agir(cible, 'ouvrir'); } },
                { label: 'Nouveau dossier…', icon: 'pi-plus', run: function () { agir(cible, 'nouveau'); } }
            ];
        }
        if (type === 'dossier') {
            return [
                { titre: el.getAttribute('data-nom') },
                { label: 'Ouvrir', icon: 'pi-folder-open', run: function () { agir(cible, 'ouvrir'); } },
                { label: 'Nouveau sous-dossier…', icon: 'pi-plus', run: function () { agir(cible, 'nouveau'); } },
                '-',
                { label: 'Renommer…', icon: 'pi-pencil', run: function () { agir(cible, 'renommer'); } },
                { label: 'Supprimer', icon: 'pi-trash', danger: true, run: function () { cliquer(cible, 'ctxSupprDos'); } }
            ];
        }
        if (type === 'cv') {
            var coche = el.classList.contains('is-checked');
            var nbSel = document.querySelectorAll('.hx-cvt [data-ctx^="cv:"].is-checked').length;
            var multi = coche && nbSel > 1;
            var lu = el.getAttribute('data-lu') === 'true';
            var l = [
                { titre: multi ? nbSel + ' CV sélectionnés' : el.getAttribute('data-nom') },
                { label: 'Ouvrir', icon: 'pi-external-link', run: function () { window.open(base + '/cv/' + id, '_blank', 'noopener'); } },
                { label: 'Télécharger', icon: 'pi-download', run: function () { window.location.href = base + '/cv/' + id + '?dl=1'; } },
                { label: 'Détails', icon: 'pi-info-circle', run: function () { agir(cible, 'details'); } },
                '-'
            ];
            if (!archives) l.push({ label: 'Ajouter à un poste…', icon: 'pi-briefcase', run: function () { agir(cible, 'poste'); } });
            l.push({ label: 'Lire avec l\'IA', icon: 'pi-sparkles', run: function () { agir(cible, 'lire'); } });
            if (lu || multi) l.push({ label: 'Relire avec l\'IA', icon: 'pi-refresh', run: function () { agir(cible, 'relire'); } });
            l.push('-');
            l.push({ label: 'Déplacer…', icon: 'pi-folder', run: function () { agir(cible, 'deplacer'); } });
            l.push({ label: archives ? 'Désarchiver' : 'Archiver', icon: 'pi-box', run: function () { agir(cible, 'archiver'); } });
            l.push({ label: 'Supprimer définitivement', icon: 'pi-trash', danger: true, run: function () { cliquer(cible, 'ctxSupprCv'); } });
            return l;
        }
        // fond du dossier
        var l2 = [];
        if (!archives) {
            l2.push({ label: 'Nouveau dossier…', icon: 'pi-plus', run: function () { agir(cible, 'nouveau'); } });
            l2.push({ label: 'Importer des CV…', icon: 'pi-upload', run: function () {
                var f = document.querySelector('.hx-cvt__upload input[type=file]');
                if (f) f.click();
            } });
        }
        if (document.querySelector('.hx-cvt [data-ctx^="cv:"]')) {
            var tous = document.querySelectorAll('.hx-cvt__icons [data-ctx^="cv:"], .hx-cvt__list [data-ctx^="cv:"]');
            var nbCoches = document.querySelectorAll('.hx-cvt__icons [data-ctx^="cv:"].is-checked, .hx-cvt__list [data-ctx^="cv:"].is-checked').length;
            l2.push({ label: nbCoches < tous.length ? 'Tout sélectionner' : 'Tout désélectionner', icon: 'pi-check-square', run: function () { agir(cible, 'tout'); } });
        }
        return l2;
    }

    function ouvrir(e, el, cible) {
        var items = entrees(el, cible);
        if (!items.length) return;
        e.preventDefault();
        fermer();
        if (el !== racine()) el.classList.add('hx-ctx-on');

        menu = document.createElement('div');
        menu.className = 'hx-ctx';
        menu.setAttribute('role', 'menu');
        items.forEach(function (it) {
            if (it === '-') { var s = document.createElement('div'); s.className = 'hx-ctx__sep'; s.setAttribute('role', 'separator'); menu.appendChild(s); return; }
            if (it.titre !== undefined) {
                if (!it.titre) return;
                var t = document.createElement('div'); t.className = 'hx-ctx__title'; t.textContent = it.titre; menu.appendChild(t); return;
            }
            var b = document.createElement('button');
            b.type = 'button';
            b.className = 'hx-ctx__item' + (it.danger ? ' is-danger' : '');
            b.setAttribute('role', 'menuitem');
            b.innerHTML = '<i class="pi ' + it.icon + '" aria-hidden="true"></i><span></span>';
            b.querySelector('span').textContent = it.label;
            b.addEventListener('click', function (ev) { ev.preventDefault(); fermer(); it.run(); });
            menu.appendChild(b);
        });
        document.body.appendChild(menu);

        var w = menu.offsetWidth, h = menu.offsetHeight;
        var x = Math.min(e.clientX, window.innerWidth - w - 8), y = e.clientY;
        if (y + h > window.innerHeight - 8) y = Math.max(8, e.clientY - h);
        menu.style.left = Math.max(8, x) + 'px';
        menu.style.top = y + 'px';
        var premier = menu.querySelector('.hx-ctx__item');
        if (premier) premier.focus({ preventScroll: true });
    }

    document.addEventListener('contextmenu', function (e) {
        var r = racine();
        if (!r || !r.contains(e.target)) { fermer(); return; }
        if (e.target.closest('input, textarea, select')) { fermer(); return; }
        var el = e.target.closest('[data-ctx]');
        if (el && r.contains(el)) { ouvrir(e, el, el.getAttribute('data-ctx')); return; }
        if (e.target.closest('.hx-cvt__main')) ouvrir(e, r, 'fond:');
    });

    document.addEventListener('mousedown', function (e) { if (menu && !menu.contains(e.target)) fermer(); }, true);
    window.addEventListener('blur', fermer);
    window.addEventListener('resize', fermer);
    document.addEventListener('scroll', fermer, true);

    document.addEventListener('keydown', function (e) {
        if (!menu) return;
        var items = Array.prototype.slice.call(menu.querySelectorAll('.hx-ctx__item'));
        var i = items.indexOf(document.activeElement);
        if (e.key === 'Escape') { e.preventDefault(); fermer(); }
        else if (e.key === 'ArrowDown') { e.preventDefault(); items[(i + 1) % items.length].focus(); }
        else if (e.key === 'ArrowUp') { e.preventDefault(); items[(i - 1 + items.length) % items.length].focus(); }
        else if (e.key === 'Tab') { fermer(); }
    });
})();
