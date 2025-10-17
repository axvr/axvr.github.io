import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@11.12.0/dist/mermaid.esm.min.mjs'

function $(sel, elem = document) { return elem.querySelector(sel) }
function $$(sel, elem = document) { return elem.querySelectorAll(sel) }

mermaid.initialize({
    startOnLoad: false,
    securityLevel: 'loose',
    theme: window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'default'
})

await mermaid.run({ nodes: $$('code.language-mermaid') })
