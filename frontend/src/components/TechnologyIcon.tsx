import './TechnologyIcon.css'
const icons: Record<string, string> = {
  java: 'java',
  'spring-boot': 'spring',
  spring: 'spring',
  sql: 'sql',
  git: 'git',
  docker: 'docker',
  react: 'react',
  javascript: 'javascript',
  typescript: 'typescript',
  python: 'python',
  html: 'html5',
  html5: 'html5',
  css: 'css3',
  css3: 'css3',
  postgresql: 'postgresql',
}
export function TechnologyIcon({ slug }: { slug: string }) {
  const icon = icons[slug]
  return icon ? (
    <img
      className="technology-icon"
      src={'/icons/technologies/' + icon + '.svg'}
      width="32"
      height="32"
      alt=""
      loading="lazy"
    />
  ) : null
}
