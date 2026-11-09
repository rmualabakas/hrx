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
        if (username) username.focus();
        else { var first = document.getElementById('frmPass:oldpass'); if (first) first.focus(); }
    });

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
