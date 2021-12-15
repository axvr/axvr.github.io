" Configure Vim for developing my website.

set path=,,src/**,resources/pages/**,resources

nnoremap <F5> :<C-u>ReplSend (uk.axvr.www.core/build)<CR>
" TODO: check file for namespace and reload current namespace.
nnoremap <F6> :<C-u>ReplSend (require 'uk.axvr.www.core :reload)<CR>

augroup website
    autocmd!
    " Rebuild website when certain files are saved.
    autocmd BufWritePost *.edn,*.md,*.html,*.css ReplSend (uk.axvr.www.core/build)
    " Close REPL on Vim exit.
    autocmd ExitPre * silent! call zepl#send("\<CR>\<C-d>\<CR>", 1)
augroup END

" Start server.
if !filereadable('.clj_port')
    echo 'Starting server and REPL...'
    call term_start('clj-socket -M:serve', {
                \   'term_name': 'Server',
                \   'term_finish': 'close',
                \   'term_kill': 'int',
                \   'norestore': 1,
                \   'hidden': 1
                \ })
    sleep 2
else
    echo 'Starting REPL...'
endif

" Start REPL.
hide Repl clj-socket
ReplSend (require 'uk.axvr.www.core :reload)
ReplSend (ns uk.axvr.www.core)
sleep 2
ReplClear

echon '  Ready.  Happy hacking!'
