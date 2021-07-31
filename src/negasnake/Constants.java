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

import snakes.Direction;

public final class Constants {

    public static final boolean DEBUG = true;

    public static final Direction[] DIR = {Direction.UP, Direction.RIGHT, Direction.DOWN, Direction.LEFT};

    public static final int APPLE_TTL         =            11;

    public static final int DEP_MAX           =            32;

    public static final int H_WIN             =    10_000_000;
    public static final int H_DRAW            =    -9_999_900;
    public static final int H_LONGER          =        10_000;
    public static final int H_APPLE_REACHABLE =         3_000;
    public static final int H_POS_CENTER      =         2_000;
    public static final int H_APPLE_CLOSER    =         1_000;
    public static final int H_POS_CONTROL     =            40;

    public static final int INF               = 2_000_000_000;

    public static final int M_A_DEAD          =          0b01;
    public static final int M_B_DEAD          =          0b10;
    public static final int M_COORDINATE      =        0x7FFF;
    public static final int M_DIR             =          0b11;

    public static final int S_X               =             2;
    public static final int S_Y               =            17;

    public static final int SEQ_LEN_MASK      =        0b1111;
    public static final int SEQ_LEN_MAX       =            12;
    public static final int SEQ_NULL          =            -1;

    public static final int T_LEN_AHEAD       =             4;
    public static final int T_MS_SEARCH       =           150;

    /**
     * Roughly the no. nodes in a ternary tree of height 12 divided by 0.75, the load factor. Note that it is prime.
     */
    public static final int TABLE_SIZE        =     1_000_003;

    /**
     * Includes some buffer time, since {@code Thread.sleep} is inaccurate.
     */
    public static final int TIMEOUT           =           970;

}
