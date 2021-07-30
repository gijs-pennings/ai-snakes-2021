# AI Snakes 2021: NegaSnake

This repository contains the [source code](src/negasnake) of NegaSnake, the bot I developed in association with [Serpentine](https://serpentine.ai) for [AI Snakes 2021](https://agrishchenko.wixsite.com/aisnakes2021). It finished 1st, with a win rate of 87%. 🎉

For setting up your workspace, refer to the [original instructions](src/snakes/README.md). 


## Rules

In this competition, bots compete in a 1v1 variant of [Snake](https://en.wikipedia.org/wiki/Snake_(video_game_genre)) on a 14x14 board. Summarized, the rules are as follows.
* Snakes move simultaneously. Bots must choose their next move within 1 second. 
* Matches end after at most 3 minutes, or when a snake crashes into the border, its own body, or the opponent's body. If both snakes crash simultaneously, the longer snake wins.
* Snakes grow by eating apples. At any time, exactly one apple is available on the board, which repositions randomly if not eaten after 11 moves (note that the official rule is imprecise).


## Strategy & implementation

The core of the NegaSnake bot is a [negamax search with alpha-beta pruning](https://en.wikipedia.org/wiki/Negamax#Negamax_with_alpha_beta_pruning). To apply it in this competition, where snakes move simultaneously, we make a 'paranoid' assumption: during the search, we move first, giving the opponent an advantage.

During the search, as few objects as possible are created. For example, instead of cloning the provided [`Snake`](src/snakes/Snake.java) class, preallocated data structures in the [`State`](src/negasnake/State.java) class are updated. In the same spirit, bitwise operations are used throughout the code (e.g. see `isFinal`). This improves performance and makes it less random (since the GC is run less often), allowing more states to be searched. Note, however, that these optimizations make it harder to understand, modify, and debug the code.

Depending on the current state, the time it takes to search to a certain depth is highly variable. For this reason, we use [iterative deepening](https://www.chessprogramming.org/Iterative_Deepening). Additionally, the search is run in a separate thread, which is interrupted when the 1 second time limit is approached. Together, these techniques maximize the search depth (30 ply is commonly reached) while preventing losing by timeout.

Considering 'good' moves first during the search results in [more alpha-beta cuts](https://en.wikipedia.org/wiki/Alpha%E2%80%93beta_pruning#Heuristic_improvements). Therefore, using results of previous searches, `getMovesOrdered` sorts moves from best to worst. These results are stored in a [transposition table](https://en.wikipedia.org/wiki/Transposition_table), that maps states (which are identified by the sequence of moves that lead to them; check `append` for details) to their score. For this we use a custom [`HashTable`](src/negasnake/HashTable.java), which has much less overhead than Java's `HashMap` and never allocates new memory. Note that we never check for actual [transpositions](https://en.wikipedia.org/wiki/Transposition_(chess)) while searching since they are exceedingly rare in Snake.

The score mentioned above is a (heuristic) evaluation of the current state. Whereas a deeper search improves tactical play ("Can I force-crash the opponent?"), a better evaluation results in stronger positional play. It is a hand-crafted combination of the following metrics.
* Whether the current state is a win/loss/draw. A draw is considered a loss. Earlier wins and later losses are preferred.
* The (difference in) snake lengths.
* The distance to the current apple. If one snake can reach the apple in time but its opponent cannot, that snake is rewarded additionally.
* The positioning *directly* after the apple repositions. In particular, for both snakes, we consider the number of unoccupied squares within 11 steps (the time-to-live of an apple) that are strictly closer to the snake's head than its opponent's. This is calculated using two relatively expansive [BFSs](https://en.wikipedia.org/wiki/Breadth-first_search). This score is then scaled according to the ratio of 'controlled space'. For example, if one snake controls 3x as many squares, the score is boosted by 75%. Note that only this metric is calculated during the search instead of at leaf nodes.

One last noteworthy aspect of NegaSnake's strategy, separate from the search, is its deliberate prolonging of moves. If it has a significant lead (≥4 apples) or crashing is unavoidable even though it's leading by length, it takes the full second to output its next move by sleeping the leftover time. Respectively, this minimizes the time for the opponent to catch up, and maximizes the probability of winning by game timeout.


## Future improvements

* Currently, the transposition table only caches scores from depths of ≤12 ply (note that a ternary tree of this height can already contain ~800k nodes). Consequently, at greater depths, moves cannot be ordered. Instead of storing *all* scores up to some depth, it should store the 'most important' scores from the whole search tree. This can be achieved using [replacement strategies](https://www.chessprogramming.org/Transposition_Table#Replacement_Strategies).
* Alpha-beta pruning can be replaced by a more efficient search algorithm, such as [PVS](https://en.wikipedia.org/wiki/Principal_variation_search). The [principal variation](https://en.wikipedia.org/wiki/Variation_(game_tree)#Principal_variation) is easily determined from the (improved) transposition table.
* Instead of immediately evaluating the position if `depth == 0`, you could first perform a [quiescence search](https://en.wikipedia.org/wiki/Quiescence_search) that, for example, continues while either snake must make a forced move. This extends the search in dangerous or promising situations.
* If the apple recently repositioned, the positioning metric is calculated at a relatively great depth, where there any many nodes. Since BFSs are quite costly performance-wise, the search is usually interrupted before having finished, leaving the bot vulnerable at specific moments during a match. This weakness can be mitigated by using a fast approximation in these scenarios. One option is to approximate the number of squares using [Voronoi cells](https://en.wikipedia.org/wiki/Voronoi_diagram) centered at the snakes' heads, ignoring obstacles. Their area is efficiently calculated using the [shoelace formula](https://en.wikipedia.org/wiki/Shoelace_formula).
* The [weights](src/negasnake/Constants.java) (and metrics themselves!) used in the evaluation function are based on intuition. Optimizing these would increase NegaSnake's strength. This can be done either manually, by having bots with different parameters compete and keeping the best, or using machine learning. Alternatively, the evaluation can be completely computed using a neural network, à la [Stockfish](https://stockfishchess.org/blog/2020/introducing-nnue-evaluation).


## Acknowledgments

NegaSnake was developed within E.S.A.I.V. Serpentine. Special thanks to my teammates Boris Muller, Tunahan Sarı, and Imre Schilstra.


## License

NegaSnake is licensed under [GPLv3](src/negasnake/LICENSE.txt) (or any later version). The framework and example bots by Luiz Araújo et al. are licensed under [APLv2](src/snakes/LICENSE.txt).
