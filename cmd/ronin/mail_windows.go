package main

import (
	_ "embed"

	"roninbrowser/internal/safelinks"
)

// Safe Mail (laptop only, 3.13): the script is added to every Personal / Private / Kids tab but stops at its
// first line unless the page is the top window of an email site. Phone and tablet have no Mail (owner, 18 Nov 2026).

//go:embed ui/inject_mail.js
var mailPageJS string

//go:embed ui/tools/scamcheck.js
var scamEngineJS string

// mailHosts: the email sites Safe Mail watches (the same list as the tests in tools/uitest/safemail.js).
const mailHosts = `/(^|\.)(mail\.google\.com|outlook\.live\.com|outlook\.office\.com|outlook\.office365\.com|mail\.yahoo\.com|mail\.rediff\.com|rediffmail\.com|mail\.zoho\.com|mail\.zoho\.in|mail\.proton\.me|mail\.aol\.com)$/`

// mailJS runs in every Personal / Private tab, but does nothing unless the page is an email site.
var mailJS = "(function(){if(window.top!==window||!" + mailHosts + ".test(location.hostname))return;" +
	"window.__roninLinkLists=" + string(safelinks.ListsJSON) + ";window.__roninScamRules=" + string(safelinks.ScamRulesJSON) + ";\n" +
	scamEngineJS + "\n" + mailPageJS + "\n})();"
