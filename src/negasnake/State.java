/* Copyright (c) 2021, Gijs Pennings
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package negasnake;

import snakes.Coordinate;
import snakes.Snake;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static negasnake.Constants.*;

public final class State implements Runnable {

    private static final int size = 14;
    private static final int area = size * size;

    /**
     * Precalculated scores that reward positions close to the center, and (heavily) punish the border & corners.
     */
    private static final int[] posScore = new int[area];
    static { prepareScores(); }

    private final int[] posDistance;
    private final int[] posQueue;

    /**
     * Preallocated arrays (of size {@code 3}) to store moves from {@code getMovesOrdered}. Clearing not required; they
     * are simply overwritten.
     */
    private final int[][] movesCache;
    private final HashTable movesScore;

    private final ArrayDeque<Coordinate> aBody; // us
    private final ArrayDeque<Coordinate> bBody; // them

    /**
     * {@code aOccupied[x + y*size] == aBody.contains(x,y) && aHead != (x,y)}
     */
    private final boolean[] aOccupied;
    /**
     * {@code bOccupied[x + y*size] == bBody.contains(x,y) && bHead != (x,y)}
     */
    private final boolean[] bOccupied;

    private int aBack;
    private int bBack;

    private int appleX;
    private int appleY;
    /**
     * No. turns until apple moves to new, unknown location. At that point, this field becomes negative.
     */
    private int appleTTL;

    private long time0;
    public int bestMove;
    /**
     * {@code true} iff the game is lost due to forced moves, but a draw or win by length. In this case, take as much
     * time as possible.
     */
    public boolean prolong;

    public State() {
        posDistance = new int[area];
        posQueue = new int[area];

        movesCache = new int[2*DEP_MAX + 1][3];
        movesScore = new HashTable();

        aBody = new ArrayDeque<>(area / 4);
        bBody = new ArrayDeque<>(area / 4);

        aOccupied = new boolean[area];
        bOccupied = new boolean[area];
    }

    private static void prepareScores() {
        // 1. for upper left quadrant, sum distances to all other cells
        final int m = (size+1) / 2;
        for (int y = 0; y < m; y++) {
            for (int x = 0; x < m; x++)
                for (int x2 = 0; x2 < size; x2++)
                    for (int y2 = 0; y2 < size; y2++)
                        posScore[x + y*size] += Math.abs(x - x2) + Math.abs(y - y2);

            // 2a. upper right quadrant follows by symmetry
            for (int x = m; x < size; x++)
                posScore[x + y*size] = posScore[size-1-x + y*size];
        }

        // 2b. lower quadrants follow by symmetry
        for (int y = m; y < size; y++)
            System.arraycopy(posScore, (size-1-y)*size, posScore, y*size, size);

        // 3. rescale
        int sum = 0;
        for (final int x : posScore) sum += x;

        final double avg = (double) sum / posScore.length;
        final double f = (double) H_POS_CENTER / (posScore[0] - posScore[(m-1) * (1+size)]);

        for (int i = 0; i < posScore.length; i++)
            posScore[i] = (int) Math.round(f * (avg - posScore[i]));
    }

    /**
     * @return non-zero value if current state is final (1st bit indicates whether A died, 2nd whether B died)
     */
    private int isFinal() {
        final Coordinate ah = aBody.getFirst();
        final Coordinate bh = bBody.getFirst();

        final int ai = ah.x + ah.y * size;
        final int bi = bh.x + bh.y * size;

        final boolean headCollision = ah.x == bh.x && ah.y == bh.y;
        return (aOccupied[ai] || bOccupied[ai] || headCollision ? M_A_DEAD : 0)
             | (bOccupied[bi] || aOccupied[bi] || headCollision ? M_B_DEAD : 0);
    }

    /**
     * @param h     positioning score
     * @param depth remaining depth
     * @param flags result from {@code isFinal}
     */
    private int heuristic(int h, final int depth, final int flags) {
        final int as = aBody.size();
        final int bs = bBody.size();

        // metric: win/loss/draw
        if ((flags & M_A_DEAD) > 0)
            if ((flags & M_B_DEAD) > 0)
                return (as == bs) ? (H_DRAW - depth) : (as < bs ? -1 : 1) * (H_WIN + depth);
            else
                return -(H_WIN + depth);
        else if ((flags & M_B_DEAD) > 0)
            return H_WIN + depth;

        // metric: length difference
        h += H_LONGER * (as - bs);

        // metric: apple reachability
        if (appleTTL > 0) {
            final Coordinate ah = aBody.getFirst();
            final Coordinate bh = bBody.getFirst();
            final int am = appleTTL - Math.abs(appleX - ah.x) - Math.abs(appleY - ah.y);
            final int bm = appleTTL - Math.abs(appleX - bh.x) - Math.abs(appleY - bh.y);
            if (am >= 0)
                if (bm >= 0)
                    return h + H_APPLE_CLOSER * (am - bm);
                else
                    return h + H_APPLE_REACHABLE + H_APPLE_CLOSER * Math.min(am, 5);
            else if (bm >= 0)
                return h - H_APPLE_REACHABLE - H_APPLE_CLOSER * Math.min(bm, 5);
        }
        return h;
    }

    private int heuristicPositioning(final boolean exact) {
        final Coordinate ah = aBody.getFirst();
        final Coordinate bh = bBody.getFirst();

        final int ahi = ah.x + ah.y * size;
        final int bhi = bh.x + bh.y * size;

        if (!exact) return posScore[ahi] - posScore[bhi];

        // temporarily (mis)use aOccupied to store whether cells are occupied by one of *both* snakes
        for (final Coordinate c : bBody) aOccupied[c.x + c.y * size] = true;
        aOccupied[ahi] = aOccupied[bhi] = true;

        Arrays.fill(posDistance, 0);
        int aCount = 0;
        int bCount = 0;

        // bfs for A
        posDistance[ahi] = 1;
        posQueue[0] = ahi;
        int length = 1;
        for (int k = 0, j; k < length; k++) {
            final int i = posQueue[k];
            final int d = posDistance[i] + 1;
            final int x = i % size;
            final boolean recur = d <= APPLE_TTL;

            // up
            if (i < area-size && !aOccupied[j = i+size] && posDistance[j] == 0) {
                aCount++;
                posDistance[j] = d;
                if (recur) posQueue[length++] = j;
            }

            // right
            if (x < size-1 && !aOccupied[j = i+1] && posDistance[j] == 0) {
                aCount++;
                posDistance[j] = d;
                if (recur) posQueue[length++] = j;
            }

            // down
            if (i >= size && !aOccupied[j = i-size] && posDistance[j] == 0) {
                aCount++;
                posDistance[j] = d;
                if (recur) posQueue[length++] = j;
            }

            // left
            if (x > 0 && !aOccupied[j = i-1] && posDistance[j] == 0) {
                aCount++;
                posDistance[j] = d;
                if (recur) posQueue[length++] = j;
            }
        }

        // bfs for B
        posDistance[bhi] = -1;
        posQueue[0] = bhi;
        length = 1;
        for (int k = 0, j; k < length; k++) {
            final int i = posQueue[k];
            final int d = posDistance[i] - 1;
            final int D = -d;
            final int x = i % size;
            final boolean recur = D <= APPLE_TTL;

            // up
            if (i < area-size && !aOccupied[j = i+size]) {
                final int t = posDistance[j];
                if (t == 0 || t >= D) {
                    if (t != 0) aCount--;
                    if (t != D) bCount++;
                    posDistance[j] = d;
                    if (recur) posQueue[length++] = j;
                }
            }

            // right
            if (x < size-1 && !aOccupied[j = i+1]) {
                final int t = posDistance[j];
                if (t == 0 || t >= D) {
                    if (t != 0) aCount--;
                    if (t != D) bCount++;
                    posDistance[j] = d;
                    if (recur) posQueue[length++] = j;
                }
            }

            // down
            if (i >= size && !aOccupied[j = i-size]) {
                final int t = posDistance[j];
                if (t == 0 || t >= D) {
                    if (t != 0) aCount--;
                    if (t != D) bCount++;
                    posDistance[j] = d;
                    if (recur) posQueue[length++] = j;
                }
            }

            // left
            if (x > 0 && !aOccupied[j = i-1]) {
                final int t = posDistance[j];
                if (t == 0 || t >= D) {
                    if (t != 0) aCount--;
                    if (t != D) bCount++;
                    posDistance[j] = d;
                    if (recur) posQueue[length++] = j;
                }
            }
        }

        // restore aOccupied array
        for (final Coordinate c : bBody) aOccupied[c.x + c.y * size] = false;
        aOccupied[ahi] = aOccupied[bhi] = false;

        final int mCount = Math.max(aCount, bCount) + 1; // prevents division by 0
        return H_POS_CONTROL * (aCount - bCount) * (Math.abs(aCount - bCount) + mCount) / mCount;
    }

    /**
     * Determines all non-suicidal moves and sorts them from best to worst according to {@code movesScore}. If there are
     * none, it returns a single move within bounds. Moves are encoded as {@code yyyyyyyy yyyyyyyx xxxxxxxx xxxxxxdd}
     * where {@code x,y,d} represent the coordinates and direction respectively.
     * @param sequence if {@code -1}, the moves will not be sorted
     * @param array    preallocated array to store the moves in
     * @return no. moves in {@code array} (i.e. its length)
     */
    private int getMovesOrdered(final boolean isA, final int sequence, final int[] array) {
        // 0. initialize
        Coordinate head, tail;
        boolean[] occupied;
        int back;

        if (isA) {
            head = aBody.getFirst(); tail = aBody.getLast(); occupied = aOccupied; back = aBack;
        } else {
            head = bBody.getFirst(); tail = bBody.getLast(); occupied = bOccupied; back = bBack;
        }

        // 1. generate
        int backup = 0;
        int count = 0;

        for (int d = 0; d < DIR.length; d++) {
            // i. check if going backwards
            if (d == back) continue;

            // ii. check if outside bounds
            final int x = head.x + DIR[d].dx;
            if (x < 0 || x >= size) continue;
            final int y = head.y + DIR[d].dy;
            if (y < 0 || y >= size) continue;

            final int i = x + y * size;
            backup = d | (x << S_X) | (y << S_Y); // at least one move will always reach this point!

            // iii. check if hitting own body
            if (occupied[i] && !(x == tail.x && y == tail.y)) continue;

            // iv. check if hitting opponent's body
            if (isA) {
                // since we move *before* them, bOccupied is outdated!
             /* final Coordinate bHead = bBody.getFirst(); */
                final Coordinate bTail = bBody.getLast();
                if (bOccupied[i] && !(x == bTail.x && y == bTail.y) /* || (x == bHead.x && y == bHead.y) */) continue;
            } else {
                // since they move *after* us, aOccupied is up to date ✓
                if (aOccupied[i]) continue;
            }

            // else ..
            array[count++] = backup;
        }

        if (count == 0) {
            array[count++] = backup;
        } else if (sequence != SEQ_NULL) {
            // 2. sort (note that: descending of negatives == ascending)
            if (count == 2) {
                if (movesScore.get(append(sequence, array[0] & M_DIR)) > movesScore.get(append(sequence, array[1] & M_DIR))) {
                    final int t = array[0]; array[0] = array[1]; array[1] = t;
                }
            } else if (count == 3) {
                final int s0 = movesScore.get(append(sequence, array[0] & M_DIR));
                final int s1 = movesScore.get(append(sequence, array[1] & M_DIR));
                final int s2 = movesScore.get(append(sequence, array[2] & M_DIR));

                if (s0 <= s1) {
                    if (s1 > s2)
                        if (s0 < s2) {
                            final int t = array[1]; array[1] = array[2]; array[2] = t;
                        } else {
                            final int t = array[0]; array[0] = array[2]; array[2] = array[1]; array[1] = t;
                        }
                } else {
                    if (s1 < s2) {
                        if (s0 < s2) {
                            final int t = array[0]; array[0] = array[1]; array[1] = t;
                        } else {
                            final int t = array[0]; array[0] = array[1]; array[1] = array[2]; array[2] = t;
                        }
                    } else {
                        final int t = array[0]; array[0] = array[2]; array[2] = t;
                    }
                }
            }
        }

        return count;
    }

    private int search(final int depth, int a, final int b, final int sequence, int score, final boolean eaten) throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();

        final boolean isA = depth % 2 == 0;

        // stop if leaf node
        if (isA) {
            appleTTL -= eaten ? INF : 1;
            final int flags = isFinal();
            if (flags == 0)
                if (appleTTL == -1 || eaten)
                    score = heuristicPositioning(true);
                else if (appleTTL >= 0)
                    score = heuristicPositioning(false); // fast approximation in case search does not reach sufficient depth
            if (depth == 0 || flags != 0) {
                final int h = heuristic(score, depth, flags);
                appleTTL += eaten ? INF : 1;
                if (sequence != SEQ_NULL) movesScore.set(sequence, h);
                return h;
            }
        }

        int v = -INF;

        final ArrayDeque<Coordinate> body = isA ? aBody : bBody;
        final boolean[] occupied = isA ? aOccupied : bOccupied;
        final int back = isA ? aBack : bBack;
        final Coordinate oldHead = body.getFirst();

        // consider all moves
        final int[] moves = movesCache[depth - 1];
        final int moveCount = getMovesOrdered(isA, depth <= 2 ? SEQ_NULL : sequence, moves);
        for (int i = 0; i < moveCount; i++) {
            final int m = moves[i];

            // 1a. play (head)
            final Coordinate head = new Coordinate(m >> S_X & M_COORDINATE, m >> S_Y & M_COORDINATE);
            body.addFirst(head);
            occupied[oldHead.x + oldHead.y * size] = true;

            // 1b. play (tail)
            final boolean grow = appleX == head.x && appleY == head.y && appleTTL >= 0;
            Coordinate tail = null;
            if (!grow) {
                tail = body.removeLast();
                occupied[tail.x + tail.y * size] = false;
            }

            // 1c. play (back)
            if (isA) aBack = m+2 & M_DIR; else bBack = m+2 & M_DIR;

            // 2. recur
            v = Math.max(-search(
                    depth - 1,
                    -b,
                    -a,
                    sequence == SEQ_NULL || (sequence & SEQ_LEN_MASK) >= SEQ_LEN_MAX ? SEQ_NULL : append(sequence, m & M_DIR),
                    score,
                    grow || (eaten && !isA)
            ), v);

            // 3a. undo (head)
            body.removeFirst();
            occupied[oldHead.x + oldHead.y * size] = false;

            // 3b. undo (tail)
            if (!grow) {
                body.addLast(tail);
                occupied[tail.x + tail.y * size] = true;
            }

            // 4. cut?
            if (v >= b) break;
            a = Math.max(a, v);
        }

        if (isA) {
            appleTTL += eaten ? INF : 1;
            aBack = back;
        } else {
            bBack = back;
        }

        if (sequence != SEQ_NULL) movesScore.set(sequence, v); // overwrites!
        return v;
    }

    private void searchFirst(final int depth) throws InterruptedException {
        appleTTL--; // >= 0

        int v = -INF; // == a
        int best = -1;

        final int back = aBack;
        final Coordinate oldHead = aBody.getFirst();

        // consider all moves
        final int[] moves = movesCache[depth - 1];
        final int moveCount = getMovesOrdered(true, depth <= 2 ? SEQ_NULL : 0, moves);
        for (int i = 0; i < moveCount; i++) {
            final int m = moves[i];

            // 1a. play (head)
            final Coordinate head = new Coordinate(m >> S_X & M_COORDINATE, m >> S_Y & M_COORDINATE);
            aBody.addFirst(head);
            aOccupied[oldHead.x + oldHead.y * size] = true;

            // 1b. play (tail)
            final boolean grow = appleX == head.x && appleY == head.y;
            Coordinate tail = null;
            if (!grow) {
                tail = aBody.removeLast();
                aOccupied[tail.x + tail.y * size] = false;
            }

            // 1c. play (back)
            aBack = m+2 & M_DIR;

            // 2. recur
            final int u = -search(
                    depth - 1,
                    -INF,
                    -v,
                    append(0, m & M_DIR),
                    0,
                    grow
            );
            if (v < u) { v = u; best = m & M_DIR; }

            // 3a. undo (head)
            aBody.removeFirst();
            aOccupied[oldHead.x + oldHead.y * size] = false;

            // 3b. undo (tail)
            if (!grow) {
                aBody.addLast(tail);
                aOccupied[tail.x + tail.y * size] = true;
            }
        }

        appleTTL++;
        aBack = back;

        bestMove = best;
        prolong = v <= -H_WIN && aBody.size() >= bBody.size();
    }

    @Override
    public void run() {
        int depth = 0;
        long elapsed;

        do {
            depth++;
            try {
                searchFirst(2 * depth);
            } catch (final Exception e) {
                if (DEBUG) { depth--; System.out.println("[NegaSnake] interrupted! " + e.getClass().getSimpleName()); }
                break;
            }
            elapsed = System.currentTimeMillis() - time0;
        } while (elapsed < T_MS_SEARCH && depth < DEP_MAX);

        if (DEBUG) System.out.print("[NegaSnake] depth: " + depth);
    }

    public void reset(final Snake a, final Snake b, final Coordinate apple, final int appleTTL, final long time0) {
        final List<Coordinate> aBodyList = (LinkedList<Coordinate>) a.body;
        final List<Coordinate> bBodyList = (LinkedList<Coordinate>) b.body;

        final Coordinate aHead = aBodyList.get(0);
        final Coordinate bHead = bBodyList.get(0);

        movesScore.clear();

        aBody.clear();
        bBody.clear();
        aBody.addAll(aBodyList);
        bBody.addAll(bBodyList);

        Arrays.fill(aOccupied, false);
        Arrays.fill(bOccupied, false);
        for (final Coordinate c : aBody) aOccupied[c.x + c.y * size] = true;
        for (final Coordinate c : bBody) bOccupied[c.x + c.y * size] = true;
        aOccupied[aHead.x + aHead.y * size] = false;
        bOccupied[bHead.x + bHead.y * size] = false;

        aBack = getDirection(aHead, aBodyList.get(1));
        bBack = getDirection(bHead, bBodyList.get(1));

        appleX = apple.x;
        appleY = apple.y;
        this.appleTTL = appleTTL;

        this.time0 = time0;
        bestMove = -1;
        prolong = false;
    }

    /**
     * Adds new move ({@code d}) to sequence ({@code seq}). The first 4 bits of the sequence encode
     * its length; each following pair encodes one move. Its length must be at most 12.
     */
    private static int append(final int seq, final int d) {
        return seq + 1 | d << 2*(2 + (seq & SEQ_LEN_MASK));
    }

    /**
     * @param c1 a position directly adjacent to {@code c0}
     * @return {@code i} such that {@code Constants.DIR[i]} is the direction from {@code c0} to {@code c1}
     */
    private static int getDirection(final Coordinate c0, final Coordinate c1) {
        return (c0.x == c1.x) ? (1 + c0.y - c1.y) : (2 + c0.x - c1.x);
    }

}
