/**
 * @jest-environment jsdom
 */
const { renderWord, renderCardWord, GENDERS } = require('../../main/assets/renderer.js');
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
    expect($('.definitions .definition').text()).toContain('Parte superior de la cara');
    expect($('.definitions .gloss .grammar').text()).toContain('f.');
    expect($('.examples li').text()).toBe('Ejemplo 1');
    expect($('.idiom-heading').text()).toBe('al frente');
});

test('renders simple markup inside definition text', () => {
    const word = {
        mTitle: 'cap',
        definitions: [
            {
                glosses: [
                    {
                        definition: 'El <b>cap d\'any</b> és el primer dia de l\'any.',
                        grammar: 'nom masculí',
                        gender: '',
                        examples: []
                    },
                    {
                        definition: 'En gramàtica, ... <i>Jo canto</i> és primera persona.',
                        grammar: 'nom femení',
                        gender: '',
                        examples: []
                    }
                ]
            }
        ],
        idioms: [
            {
                idiom: 'fa cap',
                glosses: [
                    {
                        definition: 'Una persona o un camí <b>fa cap</b> a un lloc quan hi arriba o hi porta.',
                        grammar: 'frase feta',
                        gender: '',
                        examples: []
                    }
                ]
            }
        ]
    };

    renderWord(word);

    expect($('.definitions .gloss:eq(0) .definition b').text()).toBe("cap d'any");
    expect($('.definitions .gloss:eq(1) .definition i').text()).toBe('Jo canto');
    expect($('section.idiom .definition b').text()).toBe('fa cap');
    // Plain-text read still yields the running copy with the markup inline.
    expect($('.definitions .gloss:eq(0) .definition').text())
        .toBe("El cap d'any és el primer dia de l'any.");
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

    expect($('.definitions .domain').text()).toBe('meteorología');
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

    expect($('.definitions .geo').text()).toBe('América');
    expect($('section.idiom .geo').text()).toBe('América');
    expect($('section.idiom .gloss .grammar').text()).toBe('locución adverbial');
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

    expect($('.definitions .domain').length).toBe(0);
    expect($('.definitions .geo').length).toBe(0);
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

    expect($('.definitions .plev').text()).toBe('malsonante');
    expect($('section.idiom .plev').text()).toBe('malsonante');
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

    expect($('.definitions .plev').length).toBe(0);
    expect($('section.idiom .plev').length).toBe(0);
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

    expect($('.definitions .gloss').length).toBe(2);
    expect($('.definitions .grammar.feminine').length).toBe(1);
    expect($('.definitions .grammar.masculine').length).toBe(1);
    expect($('.grammar.feminine').closest('.gloss').find('.definition').text()).toContain('Parte superior de la cara');
    expect($('.grammar.masculine').closest('.gloss').find('.definition').text()).toContain('Zona de contacto');
    expect($('section.idiom .gloss .grammar').hasClass('feminine')).toBe(false);
});

test('exposes shared gender constants', () => {
    expect(GENDERS.FEMININE).toBe('femenino');
    expect(GENDERS.MASCULINE).toBe('masculino');
});

/* ---- Homonym page (combined entries with anchor navigation) ---- */

const homonymWord = (entries, xrefs = ['1']) => ({
    mTitle: entries[0].mTitle,
    xrefs,
    mHomonymEntries: entries
});

const entry = (title, ref) => ({
    mTitle: title,
    ref,
    dictionary: 'EST',
    definitions: [
        {
            glosses: [
                { definition: `definition of ${title}`, grammar: 'nombre masculino', gender: '', examples: [] }
            ]
        }
    ],
    idioms: []
});

test('renders all homonym entries on one page with a per-entry nav row', () => {
    const word = homonymWord([entry('frente', '1'), entry('frente', '2'), entry('frente', '3')]);

    renderWord(word);

    expect($('.homonym-entry').length).toBe(3);
    expect($('#hom-1').length).toBe(1);
    expect($('#hom-2').length).toBe(1);
    expect($('#hom-3').length).toBe(1);
    // One nav row per entry, no sticky bar
    expect($('.homonym-navbar').length).toBe(0);
    expect($('.homonym-nav').length).toBe(3);

    // Each entry's own heading and definition render
    expect($('#hom-1 h1').text()).toBe('frente');
    expect($('#hom-3 h1').text()).toBe('frente');
    expect($('#hom-2 .definition').text()).toContain('definition of frente');
});

test('per-entry nav marks that entry as current; others are #hom-N links', () => {
    const word = homonymWord([entry('frente', '1'), entry('frente', '2')]);

    renderWord(word);

    // Row above entry 1: entry 1 is current, entry 2 is a link
    const row1 = $('#hom-1 > .homonym-nav');
    expect(row1.find('.homonym-current').attr('href')).toBe('#hom-1');
    expect(row1.find('.homonym-link').attr('href')).toBe('#hom-2');

    // Row above entry 2: entry 2 is current, entry 1 is a link
    const row2 = $('#hom-2 > .homonym-nav');
    expect(row2.find('.homonym-current').attr('href')).toBe('#hom-2');
    expect(row2.find('.homonym-link').attr('href')).toBe('#hom-1');

    expect($('.homonym-nav a[href]').filter((_, el) => !$(el).attr('href').startsWith('#hom-')).length).toBe(0);
});

test('duplicate headword titles are numbered RAE-style in the nav', () => {
    const word = homonymWord([entry('cura', '1'), entry('cura', '2'), entry('cura', '3')]);

    renderWord(word);

    const tails = $('#hom-1 > .homonym-nav a').map((_, el) => $(el).clone().find('.homonym-ordinal').remove().end().text().trim()).get();
    expect(tails).toEqual(['cura', 'cura', 'cura']);
    const ordinals = $('#hom-1 > .homonym-nav .homonym-ordinal').map((_, el) => $(el).text()).get();
    expect(ordinals).toEqual(['1', '2', '3']);
});

test('distinct sub-entry titles are shown plain without ordinals', () => {
    const word = homonymWord(
        [entry('muerte', '1'), entry('muerte natural', '2'), entry('muerte violenta', '3')],
        ['1']
    );

    renderWord(word);

    const labels = $('#hom-1 > .homonym-nav a').map((_, el) => $(el).text()).get();
    expect(labels).toEqual(['muerte', 'muerte natural', 'muerte violenta']);
    expect($('.homonym-ordinal').length).toBe(0);
});

test('combined pages prefix nav labels with the dictionary tag per entry', () => {
    const dle = (title, ref) => ({ ...entry(title, ref), dictionary: 'DLE' });
    const combined = homonymWord(
        [dle('frente', '1'), entry('frente', '1'), entry('frente', '2')],
        ['DLE::1']
    );

    renderWord(combined);

    const labels = $('#hom-1 > .homonym-nav a').map((_, el) => $(el).text()).get();
    expect(labels).toEqual(['DLE frente', 'EST frente1', 'EST frente2']);
    // Ordinals are grouped per dictionary: the two EST entries get ¹/², the
    // single DLE entry stays plain.
    const ordinals = $('#hom-1 > .homonym-nav .homonym-ordinal').map((_, el) => $(el).text()).get();
    expect(ordinals).toEqual(['1', '2']);
    // A fully single-dictionary combined page keeps the plain labels.
    renderWord(homonymWord([entry('muerte', '1'), entry('muerte natural', '2')], ['EST::1']));
    const plain = $('#hom-1 > .homonym-nav a').map((_, el) => $(el).text()).get();
    expect(plain).toEqual(['muerte', 'muerte natural']);
    expect($('.homonym-ordinal').length).toBe(0);
});

test('word without homonym entries renders as a single article (no nav)', () => {
    const word = {
        mTitle: 'frente',
        definitions: [
            { glosses: [{ definition: 'Parte superior de la cara.', grammar: '', gender: '', examples: [] }] }
        ],
        idioms: []
    };

    renderWord(word);

    expect($('.homonym-navbar').length).toBe(0);
    expect($('.homonym-entry').length).toBe(0);
    expect($('article').length).toBe(1);
});

test('mHomonymEntries with a single entry does not produce a homonym page', () => {
    renderWord(homonymWord([entry('frente', '1')]));

    expect($('.homonym-navbar').length).toBe(0);
    expect($('.homonym-entry').length).toBe(0);
    expect($('article').length).toBe(1);
    expect($('h1').text()).toBe('frente');
});

test('homonym entries render their own dictionary label and morphology', () => {
    const rich = (ref) => ({
        mTitle: 'morir',
        ref,
        dictionary: 'DLE',
        conjugation: 'dormir',
        participle: 'muerto',
        etymology: 'Del lat. morī.',
        pronunciation: 'moɾir',
        definitions: [
            { glosses: [{ definition: 'Llegar al término de la vida.', grammar: 'verbo intransitivo', gender: '', examples: [] }] }
        ],
        idioms: []
    });
    renderWord(homonymWord([rich('1'), rich('2')]));

    expect($('.homonym-entry').length).toBe(2);
    expect($('.dictionary-label').length).toBe(2);
    expect($('.dictionary-label').text()).toBe('DLEDLE');
    expect($('.etymology').length).toBe(2);
    expect($('.morphology').length).toBe(2);
    expect($('.pronunciation').length).toBe(2);
});

test('combined DLE/EST entries render dictionary labels with no auto-numbering', () => {
    const dle = (title, ref) => ({ ...entry(title, ref), dictionary: 'DLE' });
    const combined = homonymWord(
        [dle('frente', '1'), entry('frente', 'EST::1')],
        ['DLE::1']
    );

    renderWord(combined);

    // Both entries carry a dictionary sub-heading…
    expect($('.dictionary-label').length).toBe(2);
    expect($('.dictionary-label').text()).toBe('DLEEST');
    // …and the definitions render as plain div rows with no auto-numbering
    // and no senseNumber markers.
    expect($('div.definitions').length).toBe(2);
    expect($('.sense-number').length).toBe(0);
    expect($('ol').length).toBe(0);
});

test('single-entry combined word renders as one unnumbered article', () => {
    // The app serializes Word.combined: a single resolved entry is presented
    // as a plain article whose `dictionary` is the source label.
    const word = {
        mTitle: 'frente',
        dictionary: 'EST',
        xrefs: ['EST::1'],
        mHomonymEntries: [entry('frente', 'EST::1')],
        definitions: entry('frente', 'EST::1').definitions,
        idioms: []
    };

    renderWord(word);

    // Renders as one article with its label…
    expect($('article').length).toBe(1);
    expect($('.dictionary-label').text()).toBe('EST');
    // …and the definitions render as plain unnumbered div rows.
    expect($('div.definitions').length).toBe(1);
    expect($('ol').length).toBe(0);
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

test('renders etymology in header', () => {
    const word = {
        mTitle: 'frente',
        etymology: 'Del antiguo fruente, y este del latín frons, frontis.',
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

    expect($('h1').text()).toBe('frente');
    expect($('.etymology').text()).toContain('Del antiguo fruente');
});

test('omits etymology element when absent', () => {
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

    expect($('.etymology').length).toBe(0);
});

test('renders pronunciation in header', () => {
    const word = {
        mTitle: 'taula',
        pronunciation: 'táwlə',
        definitions: [
            {
                glosses: [
                    { definition: 'table.', grammar: 'femení', gender: 'femenino', examples: [] }
                ]
            }
        ],
        idioms: []
    };

    renderWord(word);

    expect($('h1').text()).toBe('taula');
    expect($('.pronunciation').text()).toBe('táwlə');
});

test('omits pronunciation element when absent', () => {
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

    expect($('.pronunciation').length).toBe(0);
});

test('renders sense numbers on definitions and interleaved idioms', () => {
    const word = {
        mTitle: 'cap',
        definitions: [
            {
                senseNumber: '3.1',
                glosses: [
                    { definition: 'Persona que ocupa un lloc de preferència.', grammar: 'masculí i femení', gender: '', examples: [] }
                ]
            },
            {
                senseNumber: '1.1',
                glosses: [
                    { definition: 'Part superior del cos.', grammar: 'masculí', gender: 'masculino', examples: [] }
                ]
            }
        ],
        idioms: [
            {
                senseNumber: '1.4',
                idiom: 'abaixar el cap',
                glosses: [
                    { definition: 'Cedir al voler d’altri.', grammar: '', gender: '', examples: [] }
                ]
            }
        ]
    };

    renderWord(word);

    // The definitions render as a plain div list with the original numbers.
    expect($('div.definitions').length).toBe(1);
    expect($('ol').length).toBe(0);
    // Numbered idioms are interleaved with the senses in page order in the
    // single definitions list (as on the source page), not split out.
    expect($('div.definitions .sense-number').map((_, el) => $(el).text()).get())
        .toEqual(['1.1', '1.4', '3.1']);
    expect($('div.definitions .idiom-name').text()).toBe('abaixar el cap');
    expect($('section.idiom').length).toBe(0);
});

test('renders unnumbered div rows when sense numbers are absent', () => {
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

    expect($('div.definitions').length).toBe(1);
    expect($('ol').length).toBe(0);
    expect($('.sense-number').length).toBe(0);
});

test('renders antonyms on definitions', () => {
    const word = {
        mTitle: 'frente',
        definitions: [
            {
                synonyms: ['fachada'],
                antonyms: ['trasera', 'espalda'],
                glosses: [
                    { definition: 'Fachada o parte primera.', grammar: 'nombre masculino o femenino', gender: '', examples: [] }
                ]
            }
        ],
        idioms: []
    };

    renderWord(word);

    expect($('.antonyms .antonym').length).toBe(2);
    expect($('.antonyms .antonym').eq(0).text()).toBe('trasera');
    expect($('.antonyms .antonym').eq(1).text()).toBe('espalda');
});

test('omits antonyms div when list is empty', () => {
    const word = {
        mTitle: 'frente',
        definitions: [
            {
                synonyms: ['fachada'],
                antonyms: [],
                glosses: [
                    { definition: 'Fachada o parte primera.', grammar: 'nombre masculino o femenino', gender: '', examples: [] }
                ]
            }
        ],
        idioms: []
    };

    renderWord(word);

    expect($('.antonyms').length).toBe(0);
});

test('renders domain on idioms', () => {
    const word = {
        mTitle: 'frente',
        definitions: [],
        idioms: [
            {
                idiom: 'frente de batalla',
                domain: 'Milicia',
                glosses: [
                    { definition: 'Extensión que ocupa una porción de tropa.', grammar: 'locución nominal', gender: '', examples: [] }
                ]
            }
        ]
    };

    renderWord(word);

    expect($('section.idiom .domain').text()).toBe('Milicia');
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

    expect($('.definitions .register').text()).toBe('coloquial');
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

    expect($('section.idiom .register').text()).toBe('coloquial');
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

test('renders structured synonyms with href links and plev marker', () => {
    const word = {
        mTitle: 'morir',
        definitions: [
            {
                synonyms: [
                    { text: 'piantarse', href: 'https://dle.rae.es/?id=SrurElO', plev: '' },
                    { text: 'descoñetar', href: 'https://dle.rae.es/?id=CjYRP23', plev: 'malsonante' }
                ],
                glosses: [
                    { definition: 'Dejar de vivir.', grammar: 'verbo intransitivo', gender: '', examples: [] }
                ]
            }
        ],
        idioms: []
    };

    renderWord(word);

    const anchors = $('.synonyms a.synonym');
    expect(anchors.length).toBe(2);

    expect(anchors.eq(0).attr('href')).toBe('https://dle.rae.es/?id=SrurElO');
    expect(anchors.eq(0).text()).toBe('piantarse');
    expect(anchors.eq(0).next('.synonym-plev').length).toBe(0);

    expect(anchors.eq(1).attr('href')).toBe('https://dle.rae.es/?id=CjYRP23');
    expect(anchors.eq(1).text()).toBe('descoñetar');
    expect(anchors.eq(1).next('.synonym-plev').text()).toBe('⚠️');
    expect(anchors.eq(1).next('.synonym-plev').attr('title')).toBe('malsonante');
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

    const glosses = $('.definitions .gloss');
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

    const glosses = $('.definitions .gloss');
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

    const glosses = $('section.idiom .gloss');
    expect(glosses.length).toBe(3);

    expect(glosses.eq(0).find('.grammar').text()).toBe('expresión');
    expect(glosses.eq(0).find('.examples').length).toBe(0);

    expect(glosses.eq(1).find('.definition').text()).toBe('Se usa especialmente como grito de protesta.');
    expect(glosses.eq(1).find('.examples li').text()).toBe('Los republicanos gritaban: –¡Muera la monarquía!');

    expect(glosses.eq(2).find('.definition').text()).toBe('También nombre masculino');
    expect(glosses.eq(2).find('.grammar').text()).toBe('nombre masculino');
    expect(glosses.eq(2).find('.grammar').hasClass('masculine')).toBe(true);
    expect(glosses.eq(2).find('.examples li').text()).toBe('Los mueras contra el general ahogaban los vítores de sus partidarios.');
});

test('renders grouped idiom senses with per-gloss numbers under one header', () => {
    // EST/DLE group N numbered aceps under one locution header: one Idiom,
    // one gloss per sense, the orden on the gloss. The header appears once
    // and the senses stay in their own per-idiom section below the
    // definitions (not interleaved with the definitions like diccionari.cat
    // numbered idioms).
    const word = {
        mTitle: 'frente',
        definitions: [
            {
                senseNumber: '1',
                glosses: [
                    { definition: 'Parte superior de la cara.', grammar: 'nombre femenino', gender: '', examples: [] }
                ]
            }
        ],
        idioms: [
            {
                idiom: 'al frente',
                glosses: [
                    { definition: 'Hacia delante.', grammar: 'locución adverbial', gender: '', examples: [], senseNumber: '1' },
                    { definition: 'Dirigiendo o mandando.', grammar: 'locución adverbial', gender: '', examples: [], senseNumber: '2' },
                    { definition: 'Enfrente.', grammar: 'locución adverbial', gender: '', examples: [], senseNumber: '3' }
                ]
            }
        ]
    };

    renderWord(word);

    // One section with one h2 heading for the three senses.
    expect($('section.idiom').length).toBe(1);
    expect($('section.idiom .idiom-heading').text()).toBe('al frente');
    expect($('section.idiom .idiom-heading').prop('tagName')).toBe('H2');
    expect($('section.idioms').length).toBe(0);
    expect($('h3').filter((_, el) => $(el).text() === 'Locuciones').length).toBe(0);
    const glosses = $('section.idiom .gloss');
    expect(glosses.length).toBe(3);
    expect(glosses.map((_, el) => $(el).find('.sense-number').text()).get())
        .toEqual(['1', '2', '3']);
    // Grouped idioms keep the separate section: definitions stay in the div list.
    expect($('div.definitions .definition-row').length).toBe(1);
    expect($('div.definitions .sense-number').text()).toBe('1');
    expect($('section.idiom').length).toBe(1);
});

test('omits gloss sense numbers when absent', () => {
    const word = {
        mTitle: 'cagar',
        definitions: [
            {
                senseNumber: '1',
                glosses: [
                    { definition: 'Evacuar el vientre.', grammar: 'verbo intransitivo', gender: '', examples: [] }
                ]
            }
        ],
        idioms: [
            {
                idiom: 'cagarla',
                glosses: [
                    { definition: 'Cometer un error de difícil solución.', grammar: 'locución verbal', gender: '', examples: [] }
                ]
            }
        ]
    };

    renderWord(word);

    expect($('.definitions .gloss .sense-number').length).toBe(0);
    expect($('section.idiom .gloss .sense-number').length).toBe(0);
    expect($('.definitions .definition-row > .sense-number').text()).toBe('1');
});
test('renders dictionary label for bilingual word', () => {
    const word = {
        mTitle: 'frente',
        dictionary: 'Collins Spanish-English',
        definitions: [
            {
                pos: 'feminine noun',
                grammar: 'feminine noun',
                glosses: [
                    { definition: '<span lang="en-gb" class="cit type-translation"><span class="quote">forehead</span></span>', senseNumber: '1', examples: [] }
                ],
                idioms: [{ headword: 'frente a frente', translation: '<span class="quote">face to face</span>', examples: [] }],
                phrases: [{ headword: 'al frente', translation: '<span class="quote">at the front</span>', examples: [] }]
            }
        ]
    };

    renderWord(word);

    expect($('.dictionary-label').text()).toBe('Collins Spanish-English');
    expect($('div.definitions').length).toBe(1);
    expect($('ol').length).toBe(0);
    const row = $('.definitions .definition-row').first();
    expect(row.find('.pos').text()).toBe('feminine noun');
    // The extracted sensenum renders as the gloss's sense number.
    expect(row.find('.gloss .sense-number').text()).toBe('1');
    expect(row.find('.def-idioms li').length).toBe(1);
    expect(row.find('.def-idioms .idiom-headword').text()).toBe('frente a frente');
    expect(row.find('.def-idioms').find('.quote').text()).toBe('face to face');
    expect(row.find('.def-phrases li').length).toBe(1);
    expect(row.find('.def-phrases .phrase-headword').text()).toBe('al frente');
    expect(row.find('.gloss .definition').text()).toContain('forehead');
});

test('omits dictionary label and pos when absent', () => {
    const word = {
        mTitle: 'frente',
        definitions: [
            { glosses: [{ definition: 'face', examples: [] }] }
        ]
    };

    renderWord(word);

    expect($('.dictionary-label').length).toBe(0);
    expect($('div.definitions').length).toBe(1);
    expect($('.pos').length).toBe(0);
});

test('omits per-definition idioms and phrases when empty', () => {
    const word = {
        mTitle: 'frente',
        dictionary: 'Collins Spanish-English',
        definitions: [
            { pos: 'noun', glosses: [{ definition: 'face', examples: [] }], idioms: [], phrases: [] }
        ]
    };

    renderWord(word);

    expect($('.def-idioms').length).toBe(0);
    expect($('.def-phrases').length).toBe(0);
});

test('renders phrase examples', () => {
    const word = {
        mTitle: 'frente',
        dictionary: 'Collins Spanish-English',
        definitions: [
            {
                pos: 'noun',
                glosses: [{ definition: '<span>front</span>', examples: [] }],
                idioms: [],
                phrases: [
                    { headword: 'al frente de', translation: '<span class="quote">at the head of</span>', examples: ['<span>Ejemplo 1</span>', '<span>Ejemplo 2</span>'] }
                ]
            }
        ]
    };

    renderWord(word);

    const phrases = $('.def-phrases');
    expect($('.def-phrases .phrase-headword').length).toBe(1);
    expect($('.def-phrases .phrase-headword').text()).toBe('al frente de');
    // examples render inside translation rich HTML; verify translation coerced
    expect($('.def-phrases li').first().text()).toContain('at the head of');
    expect($('.def-phrases li').first().text()).toContain('Ejemplo 1');
    expect($('.def-phrases li').first().text()).toContain('Ejemplo 2');
});

test('distinguishes English translation cells from source quotes', () => {
    const word = {
        mTitle: 'frente',
        dictionary: 'Collins Spanish-English',
        definitions: [
            {
                pos: 'noun',
                glosses: [
                    {
                        definition: '<span class="sensenum bluebold">1.&nbsp;</span><span lang="en-gb" class="cit type-translation"><span class="quote">forehead</span></span>',
                        examples: ['<span class="quote">un ejército con su capitán al frente</span> <span lang="en-gb" class="cit type-translation"><span class="quote">an army led by its captain</span></span>']
                    }
                ],
                idioms: [],
                phrases: [
                    {
                        headword: 'al frente de',
                        translation: '<span lang="en-gb" class="cit type-translation"><span class="quote">at the head of</span></span>',
                        examples: ['<span class="quote">espero seguir al frente del festival</span> <span lang="en-gb" class="cit type-translation"><span class="quote">I hope to continue as director of the festival</span></span>']
                    }
                ]
            }
        ]
    };

    renderWord(word);

    // The definition has English translation cells marked with lang + cit.type-translation
    expect($('.definition span.cit.type-translation').length).toBe(1);
    expect($('.definition span.cit.type-translation').attr('lang')).toBe('en-gb');

    // The bare source quote has NO lang/cit wrapper
    const glossEx = $('.gloss .examples li:first');
    expect(glossEx.find('span.cit.type-translation').length).toBe(1);
    expect(glossEx.find('span.quote').length).toBe(2);

    // Phrase example similarly keeps source (bare quote) + translation (cit cell)
    const phraseEx = $('.def-phrases ul.def-examples li:first');
    expect(phraseEx.find('span.quote').length).toBe(2);
    expect(phraseEx.find('span[lang="en-gb"]').length).toBe(1);
    expect(phraseEx.find('span:not([lang]) .quote').length).toBe(0);
    expect(phraseEx.find('span[lang="en-gb"] .quote').length).toBe(1);
});

test('renders gloss-level sense numbers from extracted Collins sensenums', () => {
    const word = {
        mTitle: 'frente',
        dictionary: 'Collins Spanish-English',
        definitions: [
            {
                pos: 'feminine noun',
                glosses: [
                    { definition: 'forehead', senseNumber: '1', examples: [] },
                    { definition: 'front', senseNumber: '2', examples: [] }
                ]
            }
        ],
        idioms: []
    };

    renderWord(word);

    expect($('ol').length).toBe(0);
    expect($('.definitions .gloss .sense-number').map((_, el) => $(el).text()).get())
        .toEqual(['1', '2']);
    // Definition-level number is absent: each sense numbers its own gloss.
    expect($('.definitions .definition-row > .sense-number').length).toBe(0);
});

test('renders Le Robert roman/arabic/lozenge sense numbers', () => {
    const word = {
        mTitle: 'table',
        definitions: [
            { senseNumber: 'I', glosses: [{ definition: 'Meuble sur pied(s).', grammar: 'nom féminin', gender: '', examples: [] }] },
            { senseNumber: 'I.1', glosses: [{ definition: "Le meuble où l'on prend ses repas.", grammar: 'nom féminin', gender: '', examples: [] }] },
            { senseNumber: 'I.2', glosses: [{ definition: "Table servant à d'autres usages.", grammar: 'nom féminin', gender: '', examples: [] }] },
            { senseNumber: '⬥', glosses: [{ definition: 'La nourriture.', grammar: 'nom féminin', gender: '', examples: [] }] }
        ],
        idioms: []
    };

    renderWord(word);

    expect($('ol').length).toBe(0);
    expect($('.definitions .definition-row > .sense-number').map((_, el) => $(el).text()).get())
        .toEqual(['I', 'I.1', 'I.2', '⬥']);
});

test('renders RAE entry-number superscripts in headword and synonyms', () => {
    const word = {
        mTitle: 'tapa<sup>1</sup>',
        definitions: [
            {
                synonyms: [{ text: 'cara<sup>1</sup>', href: 'https://dle.rae.es/?id=7NOG7x2', plev: '' }],
                antonyms: ['vivir<sup>1</sup>'],
                glosses: [
                    { definition: 'Pieza que cierra.', grammar: 'nombre femenino', gender: '', examples: [] }
                ]
            }
        ],
        idioms: []
    };

    renderWord(word);

    // Headword keeps its superscript entry number (tapa¹, not flat tapa1).
    expect($('h1 sup').text()).toBe('1');
    expect($('h1').text()).toBe('tapa1');
    // Synonym link and antonym text keep theirs too.
    expect($('.synonyms a.synonym sup').text()).toBe('1');
    expect($('.synonyms a.synonym').text()).toBe('cara1');
    expect($('.antonyms .antonym sup').text()).toBe('1');
});

test('marks SO neuter headwords for the (ett) prefix and bold declined form', () => {
    const word = {
        mTitle: 'hus',
        gender: 't',
        conjugation: '<strong>huset</strong>, plural hus, bestämd form plural husen',
        definitions: [
            { glosses: [{ definition: 'uppbyggd konstruktion.', grammar: 'substantiv', gender: '', examples: [] }] }
        ],
        idioms: []
    };

    renderWord(word);

    expect($('h1').text()).toBe('hus');
    expect($('h1').hasClass('so-neuter')).toBe(true);
    expect($('.morphology .conjugation strong').text()).toBe('huset');
});

test('marks SO common headwords without the neuter prefix', () => {
    const word = {
        mTitle: 'kutter',
        gender: 'n',
        conjugation: 'kuttern <strong>kuttrar</strong>',
        definitions: [
            { glosses: [{ definition: 'enmastat segelfartyg.', grammar: 'substantiv', gender: '', examples: [] }] }
        ],
        idioms: []
    };

    renderWord(word);

    expect($('h1').text()).toBe('kutter');
    expect($('h1').hasClass('so-neuter')).toBe(false);
    expect($('h1').hasClass('so-common')).toBe(true);
    expect($('.morphology .conjugation strong').text()).toBe('kuttrar');
});

test('leaves h1 unmarked when word gender is absent', () => {
    const word = {
        mTitle: 'frente',
        definitions: [
            { glosses: [{ definition: 'Parte superior de la cara.', grammar: 'nombre femenino', gender: '', examples: [] }] }
        ],
        idioms: []
    };

    renderWord(word);

    expect($('h1').attr('class')).toBe('');
});

test('renders etymology below the definitions', () => {
    const word = {
        mTitle: 'table',
        etymology: 'Du latin tabula.',
        definitions: [
            { glosses: [{ definition: 'Meuble sur pied(s).', grammar: 'nom féminin', gender: '', examples: [] }] }
        ],
        idioms: []
    };

    renderWord(word);

    expect($('.etymology').text()).toContain('Du latin tabula.');
    const article = $('article');
    expect(article.find('.definitions').index()).toBeLessThan(article.find('.etymology').index());
});

/* ---- Card back (renderCardWord: the Anki Back renderer) ---- */

const cardDef = (definition, grammar = 'nombre femenino') => ({
    senseNumber: '1',
    glosses: [{ definition, grammar, gender: '', examples: [] }]
});

test('card renders a single selection as one article with a title and no nav', () => {
    renderCardWord({
        mTitle: 'otro, tra',
        dictionary: 'DLE',
        definitions: [cardDef('Dicho de una persona.')],
        idioms: []
    });

    expect($('article').length).toBe(1);
    expect($('h1').text()).toBe('otro, tra');
    expect($('.dictionary-label').text()).toBe('DLE');
    expect($('.homonym-entry').length).toBe(0);
    expect($('.homonym-nav').length).toBe(0);
    // Numbering comes from the original sense, never an auto-numbered list.
    expect($('.sense-number').first().text()).toBe('1');
    expect($('ol').length).toBe(0);
});

test('card renders an idiom-only selection with the same header path', () => {
    renderCardWord({
        mTitle: 'frente',
        dictionary: 'EST',
        definitions: [],
        idioms: [
            {
                idiom: 'al frente',
                glosses: [
                    { definition: 'Hacia delante.', grammar: 'locución adverbial', gender: '', examples: [], senseNumber: '1' }
                ]
            }
        ]
    });

    expect($('h1').text()).toBe('frente');
    expect($('section.idiom').length).toBe(1);
    expect($('section.idiom .idiom-heading').text()).toBe('al frente');
    expect($('section.idiom .grammar').text()).toBe('locución adverbial');
});

test('card keeps grammar colors on the selected definitions', () => {
    renderCardWord({
        mTitle: 'frente',
        definitions: [
            {
                domain: 'meteorología',
                glosses: [
                    { definition: 'Zona de contacto.', grammar: 'nombre masculino', gender: GENDERS.MASCULINE, examples: [] }
                ]
            }
        ],
        idioms: []
    });

    expect($('.definitions .grammar.masculine').text()).toBe('nombre masculino');
    expect($('.definitions .domain').text()).toBe('meteorología');
});

test('card renders a cross-dictionary merge as separated labeled sections', () => {
    renderCardWord({
        mTitle: 'otro, tra',
        dictionary: 'combined',
        definitions: [],
        idioms: [],
        mHomonymEntries: [
            {
                mTitle: 'otro, tra',
                ref: '',
                dictionary: 'DLE',
                definitions: [cardDef('Dicho de una persona.')],
                idioms: []
            },
            {
                mTitle: 'otro, tra',
                ref: '',
                dictionary: 'EST',
                definitions: [cardDef('Que es distinto.', 'adjetivo')],
                idioms: []
            }
        ]
    });

    // One section per dictionary with its label, separated by the shared
    // .homonym-entry rule — and no nav rows (dead anchors in Anki).
    expect($('.homonym-entry').length).toBe(2);
    expect($('.homonym-nav').length).toBe(0);
    const labels = $('.homonym-entry .dictionary-label').map((_, el) => $(el).text()).get();
    expect(labels).toEqual(['DLE', 'EST']);
    expect($('.homonym-entry').eq(0).find('.definition').text()).toContain('Dicho de una persona');
    expect($('.homonym-entry').eq(1).find('.definition').text()).toContain('Que es distinto');
});
