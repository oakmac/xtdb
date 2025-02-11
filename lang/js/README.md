# XTDB JavaScript/TypeScript SDK

This is _very_ early days as yet. Particularly, it's currently missing tests and user documentation.

## Get started

1. It's published to NPM as `@xtdb/xtdb` - to add it to a project:
   - `npm install '@xtdb/xtdb'`
   - `yarn add '@xtdb/xtdb'`
2. `import Xtdb, { q, tx, ex } from '@xtdb/xtdb'`
3. `const xtdb = new Xtdb('http://localhost:3000')`
4. Transactions:
   
   ```javascript
   const txKey = await xtdb.submitTx([
   tx.putDocs('myTable',
       {"xt/id": 1, ...},
       {"xt/id": 2, ...}),

   ...
   ])
   ```
5. Queries:

   ```javascript
   const res = await node.query([
     q.from('myTable'),
     q.aggregate({rc: ex.call("row-count")})
   ])
   ```

## Publishing

1. Get yourself an account on https://www.npmjs.com/
2. Ask @jarohen for an invite to the `@xtdb` org.
3. `yarn publish --no-git-tag-version`.
   We're currently using version `0.0.0-YYYY.M.D.n` (closest approximation to a Maven snapshot).
