/*
 * Écran d'attente partagé, branché sur les callbacks d'un p:fileUpload :
 *   onstart="hxAttente.envoi(this, {...})"  oncomplete / onerror / onvalidationfailure="hxAttente.suite(this)"
 * Options : titre, icone (classe PrimeIcons), etapes (libellés), rythme (secondes de passage d'une étape à la suivante),
 * note, compteur (true : « n sur N fichiers » et barre de progression réelle ; sinon nom du fichier et barre animée).
 */
window.hxAttente = (function () {
    'use strict';
    var cfg = {}, widget = null, base = 0, t0 = 0, minuteur = null, focusAvant = null, verif = null;

    function el(id) { return document.getElementById(id); }

    function etape(n) {
        var lis = el('hxAttEtapes').children;
        for (var i = 0; i < lis.length; i++) lis[i].className = i < n ? 'is-done' : (i === n ? 'is-active' : '');
    }

    function majCompteur() {
        if (!cfg.compteur || !widget) return;
        var faits = Math.max(0, (widget.uploadedFileCount || 0) - base);
        var reste = widget.files ? widget.files.length : 0;
        var total = Math.max(faits + reste, faits, 1);
        el('hxAttSous').textContent = faits + ' sur ' + total + ' fichier' + (total > 1 ? 's' : '') + ' traité' + (faits > 1 ? 's' : '');
        el('hxAttBar').classList.add('is-det');
        el('hxAttBar').firstElementChild.style.width = Math.round(100 * faits / total) + '%';
    }

    function tic() {
        var s = Math.floor((Date.now() - t0) / 1000);
        el('hxAttTemps').textContent = s < 60 ? s + ' s' : Math.floor(s / 60) + ' min ' + (s % 60) + ' s';
        if (cfg.rythme && cfg.etapes) {
            var n = 0;
            while (n < cfg.rythme.length && s >= cfg.rythme[n]) n++;
            etape(Math.min(n, cfg.etapes.length - 1));
        }
        majCompteur();
    }

    function ouvrir(options, w) {
        var o = el('hxAttente');
        if (!o) return;
        cfg = options || {};
        widget = w || null;
        base = widget ? (widget.uploadedFileCount || 0) : 0;
        el('hxAttTitre').textContent = cfg.titre || 'Traitement en cours';
        el('hxAttIcone').className = 'pi ' + (cfg.icone || 'pi-sparkles');
        el('hxAttTexte').textContent = cfg.note || '';
        var nom = widget && widget.files && widget.files[0] ? widget.files[0].name : '';
        el('hxAttSous').textContent = cfg.compteur ? '' : nom;
        var ol = el('hxAttEtapes');
        ol.innerHTML = '';
        (cfg.etapes || []).forEach(function (txt) {
            var li = document.createElement('li');
            li.innerHTML = '<i class="pi"></i><span></span>';
            li.lastChild.textContent = txt;
            ol.appendChild(li);
        });
        ol.hidden = !(cfg.etapes && cfg.etapes.length);
        var bar = el('hxAttBar');
        bar.classList.remove('is-det');
        bar.firstElementChild.style.width = '';
        if (o.hidden) {
            focusAvant = document.activeElement;
            o.hidden = false;
            document.body.classList.add('hx-wait-on');
            o.querySelector('.hx-wait__card').focus();
            t0 = Date.now();
        }
        etape(0);
        clearInterval(minuteur);
        minuteur = setInterval(tic, 500);
        tic();
    }

    function fermer() {
        var o = el('hxAttente');
        if (!o || o.hidden) return;
        clearInterval(minuteur); clearTimeout(verif);
        majCompteur();
        if (cfg.etapes) etape(cfg.etapes.length);
        setTimeout(function () {
            o.hidden = true;
            document.body.classList.remove('hx-wait-on');
            widget = null;
            if (focusAvant && focusAvant.focus) try { focusAvant.focus(); } catch (e) { /* élément retiré par la mise à jour ajax */ }
        }, 350);
    }

    function actifs() {
        try { return widget && widget.jq ? widget.jq.fileupload('active') : 0; } catch (e) { return 0; }
    }

    /** Après chaque fichier : met à jour le compteur et ferme quand plus aucun envoi n'est en cours (deux constats, l'envoi séquentiel enchaîne). */
    function suite(w) {
        if (!widget || (w && w !== widget)) { if (!widget) fermer(); return; }
        majCompteur();
        clearTimeout(verif);
        verif = setTimeout(function () {
            if (actifs() > 0) return;
            verif = setTimeout(function () { if (actifs() === 0) fermer(); }, 600);
        }, 300);
    }

    return {
        envoi: function (w, options) { ouvrir(options, w); },
        ouvrir: function (options) { ouvrir(options, null); },
        suite: suite,
        fermer: fermer
    };
})();
