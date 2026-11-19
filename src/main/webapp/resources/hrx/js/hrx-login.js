/* HRX — écrans d'authentification : mémorisation de l'identifiant, dialogue d'attente,
   alerte majuscules et règles du nouveau mot de passe. */
(function (window, document) {
    'use strict';

    var STORAGE_KEY = 'hrx.login.identifier';
    var redirectPending = false;
    var redirectTimer;

    function loadingStatus() { return document.querySelector('.hx-loading__status'); }
    function hasRedirect(doc) { return doc && doc.getElementsByTagName('redirect').length > 0; }
    function hideLoadingDialog() { if (window.PF && PF('loginLoadingDialog')) PF('loginLoadingDialog').hide(); }
    function setStatus(status, title, message) {
        if (!status) return;
        status.querySelector('.hx-loading__title').textContent = title;
        status.querySelector('.hx-loading__message').textContent = message;
    }

    /* Avertit quand le verrouillage majuscules est actif dans un champ mot de passe. */
    function watchCapsLock() {
        var warning = document.getElementById('capsWarning');
        if (!warning) return;
        document.addEventListener('keyup', function (ev) {
            if (!ev.target || ev.target.type !== 'password' && ev.target.id !== 'frmLogin:password') return;
            if (typeof ev.getModifierState !== 'function') return;
            warning.hidden = !ev.getModifierState('CapsLock');
        });
        document.addEventListener('focusout', function (ev) {
            if (ev.target && ev.target.id === 'frmLogin:password') warning.hidden = true;
        });
    }

    /* Changement de mot de passe : coche les règles au fil de la saisie (le serveur reste juge). */
    function watchPasswordRules() {
        var rules = document.getElementById('pwdRules');
        if (!rules) return;
        var tests = {
            len: function (v) { return v.length >= 8; },
            'case': function (v) { return /[a-zà-ÿ]/.test(v) && /[A-ZÀ-Þ]/.test(v); },
            digit: function (v) { return /\d/.test(v); },
            symbol: function (v) { return /[^A-Za-zÀ-ÿ0-9]/.test(v); }
        };
        function update() {
            var pwd = document.getElementById('frmPass:newpass');
            var conf = document.getElementById('frmPass:confirm');
            var match = document.getElementById('pwdMatch');
            var v = pwd ? pwd.value : '';
            Array.prototype.forEach.call(rules.querySelectorAll('[data-rule]'), function (li) {
                var ok = tests[li.getAttribute('data-rule')](v);
                li.classList.toggle('is-ok', ok);
                var icon = li.querySelector('i');
                if (icon) icon.className = 'pi ' + (ok ? 'pi-check-circle' : 'pi-circle');
            });
            if (match && conf) {
                if (!conf.value) { match.hidden = true; return; }
                var same = conf.value === v;
                match.hidden = false;
                match.classList.toggle('is-ok', same);
                match.textContent = same ? 'Les deux mots de passe correspondent.' : 'Les deux mots de passe sont différents.';
            }
        }
        document.addEventListener('input', function (ev) {
            if (ev.target && (ev.target.id === 'frmPass:newpass' || ev.target.id === 'frmPass:confirm')) update();
        });
    }

    window.addEventListener('load', function () {
        watchCapsLock();
        watchPasswordRules();
        var username = document.getElementById('frmLogin:username');
        var remember = document.getElementById('frmLogin:remember_input');
        try {
            var saved = window.localStorage.getItem(STORAGE_KEY);
            if (username && saved && !username.value) {
                username.value = saved;
                if (remember) remember.checked = true;
            }
        } catch (ignored) { /* stockage local indisponible */ }
        var password = document.getElementById('frmLogin:password');
        // Identifiant déjà mémorisé : le curseur va directement au mot de passe.
        if (username) (username.value && password ? password : username).focus();
        else { var first = document.getElementById('frmPass:oldpass'); if (first) first.focus(); }
    });

    /* Horloge du bandeau (heure du poste). */
    function tickClock() {
        var time = document.getElementById('hlClockTime');
        var date = document.getElementById('hlClockDate');
        if (!time) return;
        var now = new Date();
        time.textContent = now.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
        if (date) date.textContent = now.toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' });
    }
    tickClock();
    window.setInterval(tickClock, 15000);

    /* Parcours du collaborateur : illustration qui enchaîne les étapes (aucune donnée réelle). */
    var ETAPES = [
        ['pi-search', 'Candidature', 'Présélection dans la CVthèque'],
        ['pi-comments', 'Entretien', 'Rencontre avec le futur N+1'],
        ['pi-hourglass', 'Période d\'essai', 'Évaluation de fin d\'essai'],
        ['pi-flag', 'Objectifs', 'Fiche signée par les deux parties'],
        ['pi-star', 'Évaluation', 'N+1, directeur, validation DCHT'],
        ['pi-book', 'Formation', 'Plan de formation et évaluations']
    ];
    var etapeIndex = 0;
    var reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    function etapeSuivante() {
        var bloc = document.getElementById('hlEtape');
        var ico = document.getElementById('hlEtapeIco');
        var nom = document.getElementById('hlEtapeNom');
        var det = document.getElementById('hlEtapeDet');
        var jalons = document.getElementById('hlJalons');
        if (!bloc || !ico || !nom || !det || document.hidden) return;
        etapeIndex = (etapeIndex + 1) % ETAPES.length;
        bloc.classList.remove('is-in');
        bloc.classList.add('is-out');
        window.setTimeout(function () {
            var e = ETAPES[etapeIndex];
            ico.className = 'pi ' + e[0];
            nom.textContent = e[1];
            det.textContent = e[2];
            if (jalons) Array.prototype.forEach.call(jalons.children, function (li, i) {
                li.classList.toggle('is-on', i === etapeIndex);
                li.classList.toggle('is-done', i < etapeIndex);
            });
            bloc.classList.remove('is-out');
            void bloc.offsetWidth; // relance l'animation d'entrée
            bloc.classList.add('is-in');
        }, 300);
    }
    if (!reduceMotion) window.setInterval(etapeSuivante, 3800);

    window.rememberLoginIdentifier = function () {
        var username = document.getElementById('frmLogin:username');
        var remember = document.getElementById('frmLogin:remember_input');
        try {
            if (remember && remember.checked && username && username.value.trim()) {
                window.localStorage.setItem(STORAGE_KEY, username.value.trim());
            } else {
                window.localStorage.removeItem(STORAGE_KEY);
            }
        } catch (ignored) { /* la connexion reste possible sans stockage */ }
    };

    window.prepareLoginLoadingDialog = function () {
        var status = loadingStatus();
        window.clearTimeout(redirectTimer);
        redirectPending = false;
        if (status) {
            status.classList.remove('is-success');
            setStatus(status, 'Connexion sécurisée', 'Vérification de vos accès…');
        }
    };

    window.handleLoginAjaxSuccess = function (data) {
        var redirect = hasRedirect(data) ? data.getElementsByTagName('redirect')[0] : null;
        var url = redirect ? redirect.getAttribute('url') : null;
        if (!url) return false;
        redirectPending = true;
        var status = loadingStatus();
        if (status) {
            status.classList.add('is-success');
            setStatus(status, 'Connexion réussie', 'Ouverture de votre espace…');
        }
        redirectTimer = window.setTimeout(function () { window.location.assign(url); }, 600);
        return true; // PrimeFaces ne doit pas rejouer la redirection
    };

    window.finishLoginLoadingDialog = function () { if (!redirectPending) hideLoadingDialog(); };
    window.failLoginLoadingDialog = function () {
        window.clearTimeout(redirectTimer);
        redirectPending = false;
        hideLoadingDialog();
    };
}(window, document));
