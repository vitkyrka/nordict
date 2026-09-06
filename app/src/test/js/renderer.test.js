/**
 * @jest-environment jsdom
 */
const { renderWord, GENDERS } = require('../../main/assets/renderer.js');
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

test('renders plev for definition and idiom', () => {
    const word = {
        mTitle: 'cagar',
        definitions: [
            {
                grammar: 'verbo intransitivo',
                plev: 'malsonante',
                definition: 'Evacuar el vientre.',
                examples: []
            }
        ],
        idioms: [
            {
                idiom: 'cagarla',
                grammar: 'locución verbal',
                plev: 'malsonante',
                definition: 'Cometer un error de difícil solución.',
                examples: []
            }
        ]
    };

    renderWord(word);

    expect($('.definitions li .plev').text()).toBe('malsonante');
    expect($('.idiom-list li .plev').text()).toBe('malsonante');
});

test('omits plev element when absent', () => {
    const word = {
        mTitle: 'frente',
        definitions: [
            {
                grammar: 'nombre femenino',
                definition: 'Parte superior de la cara.',
                examples: []
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

    expect($('.definitions li .plev').length).toBe(0);
    expect($('.idiom-list li .plev').length).toBe(0);
});

test('colors definition based on gender', () => {
    const word = {
        mTitle: 'frente',
        definitions: [
            {
                gender: GENDERS.FEMININE,
                grammar: 'nombre femenino',
                definition: 'Parte superior de la cara.',
                examples: []
            },
            {
                gender: GENDERS.MASCULINE,
                grammar: 'nombre masculino',
                definition: 'Zona de contacto entre dos masas de aire.',
                examples: []
            }
        ],
        idioms: [
            {
                gender: '',
                idiom: 'al frente',
                definition: 'Hacia delante.',
                examples: []
            }
        ]
    };

    renderWord(word);

    expect($('.definitions li').length).toBe(2);
    expect($('.definitions li.feminine').length).toBe(1);
    expect($('.definitions li.masculine').length).toBe(1);
    expect($('.definitions li.feminine .definition').text()).toContain('Parte superior de la cara');
    expect($('.definitions li.masculine .definition').text()).toContain('Zona de contacto');
    expect($('.idiom-list li').hasClass('feminine')).toBe(false);
    expect($('.idiom-list li').hasClass('masculine')).toBe(false);
});

test('exposes shared gender constants', () => {
    expect(GENDERS.FEMININE).toBe('femenino');
    expect(GENDERS.MASCULINE).toBe('masculino');
});

test('renders conjugation and participle in header', () => {
    const word = {
        mTitle: 'morir',
        conjugation: 'dormir',
        participle: 'muerto',
        definitions: [
            {
                grammar: 'verbo intransitivo',
                definition: 'Dejar de vivir.',
                examples: ['Ha muerto en un accidente.']
            }
        ],
        idioms: []
    };

    renderWord(word);

    expect($('h1').text()).toBe('morir');
    expect($('.morphology .conjugation').text()).toContain('dormir');
    expect($('.morphology .participle').text()).toContain('muerto');
});

test('omits morphology line when no conjugation or participle', () => {
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

    expect($('.morphology').length).toBe(0);
});

test('renders register marker on definitions', () => {
    const word = {
        mTitle: 'morir',
        definitions: [
            {
                grammar: 'verbo intransitivo pronominal',
                register: 'coloquial',
                definition: 'Sentir intensamente algo.',
                examples: []
            }
        ],
        idioms: []
    };

    renderWord(word);

    expect($('.definitions li .register').text()).toBe('coloquial');
});

test('renders register marker on idioms', () => {
    const word = {
        mTitle: 'morir',
        definitions: [],
        idioms: [
            {
                idiom: 'muera',
                grammar: 'expresión',
                register: 'coloquial',
                definition: 'Expresa rechazo.',
                examples: []
            }
        ]
    };

    renderWord(word);

    expect($('.idiom-list li .register').text()).toBe('coloquial');
});

test('renders synonyms on definitions', () => {
    const word = {
        mTitle: 'morir',
        definitions: [
            {
                grammar: 'verbo intransitivo',
                definition: 'Dejar de vivir.',
                examples: [],
                synonyms: ['expirar', 'fallecer']
            }
        ],
        idioms: []
    };

    renderWord(word);

    expect($('.synonyms .synonym').length).toBe(2);
    expect($('.synonyms .synonym').eq(0).text()).toBe('expirar');
    expect($('.synonyms .synonym').eq(1).text()).toBe('fallecer');
});

test('omits synonyms div when list is empty', () => {
    const word = {
        mTitle: 'frente',
        definitions: [
            {
                grammar: 'nombre femenino',
                definition: 'Parte superior de la cara.',
                examples: [],
                synonyms: []
            }
        ],
        idioms: []
    };

    renderWord(word);

    expect($('.synonyms').length).toBe(0);
});

test('renders note on definitions and idioms', () => {
    const word = {
        mTitle: 'morir',
        definitions: [
            {
                grammar: 'verbo intransitivo',
                definition: 'Dejar de vivir.',
                note: 'También prnl.',
                examples: []
            }
        ],
        idioms: [
            {
                idiom: 'muera',
                grammar: 'expresión',
                definition: 'Expresa rechazo.',
                note: 'Se usa espec. como grito.',
                examples: []
            }
        ]
    };

    renderWord(word);

    expect($('.definitions li .note').text()).toBe('También prnl.');
    expect($('.idiom-list li .note').text()).toBe('Se usa espec. como grito.');
});

test('omits note element when absent', () => {
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

    expect($('.definitions li .note').length).toBe(0);
});
