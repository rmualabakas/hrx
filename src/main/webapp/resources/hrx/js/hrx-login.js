/* HRX — page de connexion : mémorisation de l'identifiant et dialogue d'attente. */
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

    window.addEventListener('load', function () {
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
