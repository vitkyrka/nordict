var setStar = function(on) {
    var el = document.getElementById("bookmark");
    var className = on ? "on" : "off";

    el.onclick = function() {
        ordboken.toggleStar();
        return false;
    };

    el.className = className;
};

var getPos = function(regex, html) {
	var starts = [];

	while (match = regex.exec(html)) {
		starts.push(match.index);
	}

	return starts;
};

var createLinks = function(el) {
	var regex = /([ >/"'(])([A-Za-z\u0080-\u00FF-]+)(?![^<]*>)/g;
	var html = el.innerHTML;
	var astarts = getPos(/<a/gi, html);
	var aends = getPos(/\/a>/gi, html);
	var apos = 0;

	el.innerHTML = html.replace(regex, function(m, before, a, offset) {
		if (a.length <= 2) {
			return before + a;
		}

		while (apos < aends.length && offset > aends[apos]) {
			apos++;
		}

		if (apos < aends.length && offset > astarts[apos] && offset < aends[apos]) {
			return before + a;
		}

		// U+00AD SOFT HYPHEN in SO
		a = a.replace('­', '');

		return before + '<a class="normal" href="/search/' + a + '">' + a + "</a>";
	});
};

var getCSS = function() {
    var css = "";

    if (document.title.indexOf("Danske") > 0) {
        css += `
        .documentActions { display: none; }
        .instrumentPanel { display: none; }
        .kilde { display: none; }
        .popup { display: none; }
        #cookieInformerBooklet { display: none; }
        .rel-begreber { display: none; }
        img { display: none; }
        .artikelkilde { display: none; }
        .stempel { font-weight: bold; }
        .match { font-size: 1.5em; }
        h2 { font-size: 1em; }
        .dividerSmall:before { content: "|"; }
        .inlineList a { padding: 0.2em; }
        .citat { font-style: italic; }
        div { padding: 0.1em };
        .match.neuter { color: green; }
        .match.neuter:before { content: "(et) "; }
`;
        return css;
    }


    $(document.styleSheets).each(function (index) {
        try {
            $(this.cssRules).each(function (e) {
                    css += this.cssText + "\n";
            });
        } catch (e) {
            // alert("fail: " + this.href);
        }
    })

    return css;
};

$(function() {
    var ps = document.getElementsByTagName("div");
    for (var i = 0; i < ps.length; i++) {
        createLinks(ps[i]);
    }

    ordboken.loaded();
});
