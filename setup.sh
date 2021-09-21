#!/bin/sh

# Setup repository for bulding site.
# Will connect the resources/dist/ directory to the `master` branch.

DIST_DIR="resources/dist/"

rm -r "$DIST_DIR"
mkdir -p "$DIST_DIR"
git worktree prune
git worktree add "$DIST_DIR" dist
