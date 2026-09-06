function renderWord(word) {
    const $content = $('#content');
    $content.empty();

    const $article = $('<article>');
    const $header = $('<header>');
    $header.append($('<h1>').text(word.mTitle));
    $article.append($header);

    if (word.definitions && word.definitions.length > 0) {
        const $defList = $('<ol class="definitions">');
        word.definitions.forEach(def => {
            const $li = $('<li>');
            if (def.grammar) {
                $li.append($('<span class="grammar">').text(def.grammar + ' '));
            }
            $li.append($('<span class="definition">').text(def.definition));

            if (def.examples && def.examples.length > 0) {
                const $exList = $('<ul class="examples">');
                def.examples.forEach(ex => {
                    $exList.append($('<li>').text(ex));
                });
                $li.append($exList);
            }
            $defList.append($li);
        });
        $article.append($defList);
    }

    if (word.idioms && word.idioms.length > 0) {
        const $idiomSection = $('<section class="idioms">');
        $idiomSection.append($('<h3>').text('Locuciones'));
        const $idiomList = $('<ul class="idiom-list">');
        word.idioms.forEach(idiom => {
            const $li = $('<li>');
            $li.append($('<b class="idiom-name">').text(idiom.idiom));
            $li.append(': ');
            $li.append($('<span class="idiom-definition">').text(idiom.definition));

            if (idiom.examples && idiom.examples.length > 0) {
                const $exList = $('<ul class="examples">');
                idiom.examples.forEach(ex => {
                    $exList.append($('<li>').text(ex));
                });
                $li.append($exList);
            }
            $idiomList.append($li);
        });
        $idiomSection.append($idiomList);
        $article.append($idiomSection);
    }

    $content.append($article);
}

// For Node.js testing
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderWord };
}
