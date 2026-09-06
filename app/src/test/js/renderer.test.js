/**
 * @jest-environment jsdom
 */
const { renderWord } = require('../../main/assets/renderer.js');
const $ = require('jquery');

// Setup JSDOM
beforeEach(() => {
    document.body.innerHTML = '<div id="content"></div>';
    global.$ = $;
});

test('renders basic word information', () => {
    const word = {
        mTitle: 'frente',
        definitions: [
            {
                grammar: 'f.',
                definition: 'Parte superior de la cara',
                examples: ['Ejemplo 1']
            }
        ],
        idioms: [
            {
                idiom: 'al frente',
                definition: 'Hacia delante.',
                examples: []
            }
        ]
    };

    renderWord(word);

    expect($('h1').text()).toBe('frente');
    expect($('.definitions li .definition').text()).toContain('Parte superior de la cara');
    expect($('.definitions li .grammar').text()).toContain('f.');
    expect($('.examples li').text()).toBe('Ejemplo 1');
    expect($('.idiom-name').text()).toBe('al frente');
});

test('renders domain with distinctive element', () => {
    const word = {
        mTitle: 'frente',
        definitions: [
            {
                grammar: 'nombre masculino',
                domain: 'meteorología',
                definition: 'Zona de contacto entre dos masas de aire.',
                examples: ['Un frente frío entrará por el norte.']
            }
        ],
        idioms: []
    };

    renderWord(word);

    expect($('.definitions li .domain').text()).toBe('meteorología');
    expect($('h1').text()).toBe('frente');
});

test('renders geo for definition and idiom', () => {
    const word = {
        mTitle: 'frente',
        definitions: [
            {
                grammar: 'nombre masculino',
                geo: 'América',
                definition: 'Enfrente.',
                examples: []
            }
        ],
        idioms: [
            {
                idiom: 'al frente',
                grammar: 'locución adverbial',
                geo: 'América',
                definition: 'Enfrente.',
                examples: []
            }
        ]
    };

    renderWord(word);

    expect($('.definitions li .geo').text()).toBe('América');
    expect($('.idiom-list li .geo').text()).toBe('América');
    expect($('.idiom-list li .grammar').text()).toBe('locución adverbial');
});

test('omits domain and geo elements when absent', () => {
    const word = {
        mTitle: 'frente',
        definitions: [
            {
                grammar: 'nombre femenino',
                definition: 'Parte superior de la cara.',
                examples: []
            }
        ],
        idioms: []
    };

    renderWord(word);

    expect($('.definitions li .domain').length).toBe(0);
    expect($('.definitions li .geo').length).toBe(0);
});
