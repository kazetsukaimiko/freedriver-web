// Pull request titles become the squash commit on main. The description is the body.
// Types and scopes are fixed. A breaking change needs both "!" and a BREAKING CHANGE footer.
// The body carries a Refs footer naming the card.

const types = [
  'feat',
  'fix',
  'refactor',
  'test',
  'docs',
  'chore',
  'ci',
  'build',
  'perf',
];

const scopes = [
  'auth',
  'ci',
  'dashboard',
  'deploy',
  'dispatcher',
  'docs',
  'keycloak',
  'main',
  'mosquitto',
  'mqtt',
  'observability',
  'site',
  'sms',
  'webui',
];

function normalized(parsed) {
  return String(parsed.raw || '').replace(/\r\n/g, '\n');
}

module.exports = {
  defaultIgnores: false,
  parserPreset: {
    parserOpts: {
      headerPattern: /^(\w*)(?:\(([^)]*)\))?(!)?: (.*)$/,
      headerCorrespondence: ['type', 'scope', 'breaking', 'subject'],
      noteKeywords: ['BREAKING CHANGE', 'BREAKING-CHANGE'],
    },
  },
  plugins: [
    {
      rules: {
        'refs-footer': (parsed) => {
          const raw = normalized(parsed);
          const header = raw.split('\n', 1)[0];
          if (/^chore\(main\): release 20[0-9]{2}-[0-9]{2}_r[0-9]+$/.test(header)) {
            return [true];
          }
          const parts = raw.trim().split(/\n\n/);
          const tail = parts[parts.length - 1] || '';
          const ok = parts.length >= 2 && /^Refs: #\d+\s*$/m.test(tail);
          return [ok, 'description must carry a Refs: #<card> footer'];
        },
        'breaking-marker': (parsed) => {
          const raw = normalized(parsed);
          const header = raw.split('\n', 1)[0];
          const bang = /^[a-z]+(\([^)\n]+\))?!:\s+\S/.test(header);
          const note = /(?:^|\n)BREAKING CHANGE: \S/.test(raw);
          if (bang === note) {
            return [true];
          }
          return [false, 'a breaking change uses ! in the title and a BREAKING CHANGE: footer'];
        },
      },
    },
  ],
  rules: {
    'type-enum': [2, 'always', types],
    'type-empty': [2, 'never'],
    'type-case': [2, 'always', 'lower-case'],
    'scope-enum': [2, 'always', scopes],
    'scope-empty': [2, 'never'],
    'scope-case': [2, 'always', 'lower-case'],
    'subject-empty': [2, 'never'],
    'subject-case': [0],
    'header-max-length': [2, 'always', 120],
    'body-leading-blank': [1, 'always'],
    'footer-leading-blank': [1, 'always'],
    'body-max-line-length': [0],
    'footer-max-line-length': [0],
    'refs-footer': [2, 'always'],
    'breaking-marker': [2, 'always'],
  },
};
