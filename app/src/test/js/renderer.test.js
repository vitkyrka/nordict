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
                glosses: [
                    { definition: 'Parte superior de la cara', grammar: 'f.', gender: '', examples: ['Ejemplo 1'] }
                ]
            }
        ],
        idioms: [
            {
                idiom: 'al frente',
                glosses: [
                    { definition: 'Hacia delante.', grammar: '', gender: '', examples: [] }
                ]
            }
        ]
    };

    renderWord(word);

    expect($('h1').text()).toBe('frente');
    expect($('.definitions li .definition').text()).toContain('Parte superior de la cara');
    expect($('.definitions li .gloss .grammar').text()).toContain('f.');
    expect($('.examples li').text()).toBe('Ejemplo 1');
    expect($('.idiom-name').text()).toBe('al frente');
});

test('renders domain with distinctive element', () => {
    const word = {
        mTitle: 'frente',
        definitions: [
            {
                domain: 'meteorología',
                glosses: [
                    { definition: 'Zona de contacto entre dos masas de aire.', grammar: 'nombre masculino', gender: '', examples: ['Un frente frío entrará por el norte.'] }
                ]
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
                geo: 'América',
                glosses: [
                    { definition: 'Enfrente.', grammar: 'nombre masculino', gender: '', examples: [] }
                ]
            }
        ],
        idioms: [
            {
                idiom: 'al frente',
                geo: 'América',
                glosses: [
                    { definition: 'Enfrente.', grammar: 'locución adverbial', gender: '', examples: [] }
                ]
            }
        ]
    };

    renderWord(word);

    expect($('.definitions li .geo').text()).toBe('América');
    expect($('.idiom-list li .geo').text()).toBe('América');
    expect($('.idiom-list li .gloss .grammar').text()).toBe('locución adverbial');
});

test('omits domain and geo elements when absent', () => {
    const word = {
        mTitle: 'frente',
        definitions: [
            {
                glosses: [
                    { definition: 'Parte superior de la cara.', grammar: 'nombre femenino', gender: '', examples: [] }
                ]
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
                plev: 'malsonante',
                glosses: [
                    { definition: 'Evacuar el vientre.', grammar: 'verbo intransitivo', gender: '', examples: [] }
                ]
            }
        ],
        idioms: [
            {
                idiom: 'cagarla',
                plev: 'malsonante',
                glosses: [
                    { definition: 'Cometer un error de difícil solución.', grammar: 'locución verbal', gender: '', examples: [] }
                ]
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
                glosses: [
                    { definition: 'Parte superior de la cara.', grammar: 'nombre femenino', gender: '', examples: [] }
                ]
            }
        ],
        idioms: [
            {
                idiom: 'al frente',
                glosses: [
                    { definition: 'Hacia delante.', grammar: '', gender: '', examples: [] }
                ]
            }
        ]
    };

    renderWord(word);

    expect($('.definitions li .plev').length).toBe(0);
    expect($('.idiom-list li .plev').length).toBe(0);
});

test('colors each gloss based on its gender', () => {
    const word = {
        mTitle: 'frente',
        definitions: [
            {
                glosses: [
                    { definition: 'Parte superior de la cara.', grammar: 'nombre femenino', gender: GENDERS.FEMININE, examples: [] },
                    { definition: 'Zona de contacto entre dos masas de aire.', grammar: 'nombre masculino', gender: GENDERS.MASCULINE, examples: [] }
                ]
            }
        ],
        idioms: [
            {
                idiom: 'al frente',
                glosses: [
                    { definition: 'Hacia delante.', grammar: '', gender: '', examples: [] }
                ]
            }
        ]
    };

    renderWord(word);

    expect($('.definitions li .gloss').length).toBe(2);
    expect($('.definitions li .gloss.feminine').length).toBe(1);
    expect($('.definitions li .gloss.masculine').length).toBe(1);
    expect($('.definitions li .gloss.feminine .definition').text()).toContain('Parte superior de la cara');
    expect($('.definitions li .gloss.masculine .definition').text()).toContain('Zona de contacto');
    expect($('.idiom-list li .gloss').hasClass('feminine')).toBe(false);
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
                glosses: [
                    { definition: 'Dejar de vivir.', grammar: 'verbo intransitivo', gender: '', examples: ['Ha muerto en un accidente.'] }
                ]
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
                glosses: [
                    { definition: 'Parte superior de la cara.', grammar: 'nombre femenino', gender: '', examples: [] }
                ]
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
                register: 'coloquial',
                glosses: [
                    { definition: 'Sentir intensamente algo.', grammar: 'verbo intransitivo pronominal', gender: '', examples: [] }
                ]
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
                register: 'coloquial',
                glosses: [
                    { definition: 'Expresa rechazo.', grammar: 'expresión', gender: '', examples: [] }
                ]
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
                synonyms: ['expirar', 'fallecer'],
                glosses: [
                    { definition: 'Dejar de vivir.', grammar: 'verbo intransitivo', gender: '', examples: [] }
                ]
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
                synonyms: [],
                glosses: [
                    { definition: 'Parte superior de la cara.', grammar: 'nombre femenino', gender: '', examples: [] }
                ]
            }
        ],
        idioms: []
    };

    renderWord(word);

    expect($('.synonyms').length).toBe(0);
});

test('renders each gloss with its grammar and own examples', () => {
    const word = {
        mTitle: 'morir',
        definitions: [
            {
                synonyms: ['expirar'],
                glosses: [
                    { definition: 'Dejar de vivir.', grammar: 'verbo intransitivo', gender: '', examples: ['Ha muerto en un accidente.'] },
                    { definition: 'También prnl.', grammar: '', gender: '', examples: ['Se ha muerto de un ataque al corazón.'] }
                ]
            }
        ],
        idioms: []
    };

    renderWord(word);

    const glosses = $('.definitions li .gloss');
    expect(glosses.length).toBe(2);

    expect(glosses.eq(0).find('.grammar').text()).toBe('verbo intransitivo');
    expect(glosses.eq(0).find('.definition').text()).toBe('Dejar de vivir.');
    expect(glosses.eq(0).find('.examples li').text()).toBe('Ha muerto en un accidente.');

    expect(glosses.eq(1).find('.grammar').length).toBe(0);
    expect(glosses.eq(1).find('.definition').text()).toBe('También prnl.');
    expect(glosses.eq(1).find('.examples li').text()).toBe('Se ha muerto de un ataque al corazón.');
});

test('renders headword on a pronominal gloss', () => {
    const word = {
        mTitle: 'cagar',
        definitions: [
            {
                glosses: [
                    { definition: 'Evacuar el vientre.', grammar: 'verbo intransitivo', gender: '', examples: [] },
                    { definition: 'Acobardarse o sentir miedo.', headword: 'cagarse', grammar: 'verbo intransitivo pronominal', gender: '', examples: [] }
                ]
            }
        ],
        idioms: []
    };

    renderWord(word);

    const glosses = $('.definitions li .gloss');
    expect(glosses.length).toBe(2);

    expect(glosses.eq(0).find('.headword').length).toBe(0);
    expect(glosses.eq(1).find('.headword').text()).toBe('cagarse');
    expect(glosses.eq(1).find('.definition').text()).toBe('Acobardarse o sentir miedo.');
});

test('renders idiom defP glosses with grammar and example attribution', () => {
    const word = {
        mTitle: 'morir',
        definitions: [],
        idioms: [
            {
                idiom: 'muera',
                glosses: [
                    { definition: 'Se usa seguido de un nombre de persona o cosa para expresar rechazo u odio hacia ellas.', grammar: 'expresión', gender: '', examples: [] },
                    { definition: 'Se usa especialmente como grito de protesta.', grammar: '', gender: '', examples: ['Los republicanos gritaban: –¡Muera la monarquía!'] },
                    { definition: 'También nombre masculino', grammar: 'nombre masculino', gender: GENDERS.MASCULINE, examples: ['Los mueras contra el general ahogaban los vítores de sus partidarios.'] }
                ]
            }
        ]
    };

    renderWord(word);

    const glosses = $('.idiom-list li .gloss');
    expect(glosses.length).toBe(3);

    expect(glosses.eq(0).find('.grammar').text()).toBe('expresión');
    expect(glosses.eq(0).find('.examples').length).toBe(0);

    expect(glosses.eq(1).find('.definition').text()).toBe('Se usa especialmente como grito de protesta.');
    expect(glosses.eq(1).find('.examples li').text()).toBe('Los republicanos gritaban: –¡Muera la monarquía!');

    expect(glosses.eq(2).find('.definition').text()).toBe('También nombre masculino');
    expect(glosses.eq(2).find('.grammar').text()).toBe('nombre masculino');
    expect(glosses.eq(2).hasClass('masculine')).toBe(true);
    expect(glosses.eq(2).find('.examples li').text()).toBe('Los mueras contra el general ahogaban los vítores de sus partidarios.');
});