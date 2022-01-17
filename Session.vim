" Configure Vim for developing my website.

set path=,,src/**,resources/pages/**,resources

nnoremap <F5> :<C-u>ReplSend (uk.axvr.www.core/build)<CR>
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
                \   'term_kill': 'int',
                \   'norestore': 1,
                \   'hidden': 1
                \ })
    sleep 3
else
    echo 'Starting REPL...'
endif

" Start REPL.
hide Repl clj-socket
call clojure#Require('uk.axvr.www.core', 1)
call clojure#ChangeNs('uk.axvr.www.core')

echon '  Ready.  Happy hacking!'
