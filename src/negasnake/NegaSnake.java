package negasnake;

import snakes.Bot;
import snakes.Coordinate;
import snakes.Direction;
import snakes.Snake;

import static negasnake.Constants.*;

public class NegaSnake implements Bot {

    private Coordinate apple;
    private int appleTTL;

    private State state;

    @Override
    public Direction chooseDirection(Snake snake, Snake opponent, Coordinate mazeSize, Coordinate apple) {
        final long time0 = System.currentTimeMillis();

        // 1. bookkeeping
        if (this.apple == null || this.apple.x != apple.x || this.apple.y != apple.y) {
            this.apple = apple;
            appleTTL = APPLE_TTL;
        } else {
            appleTTL--;
            if (appleTTL == 0) appleTTL = APPLE_TTL; // respawned in same place!?
        }
        if (state == null) state = new State();
        state.reset(snake, opponent, apple, appleTTL, time0);

        // 2. search for best move
        final Thread thread = new Thread(state);
        thread.start();
        try {
            // wait until search finished (for max ~1s)
            thread.join(TIMEOUT - System.currentTimeMillis() + time0);

            // if still searching, request to finish ASAP
            if (thread.isAlive()) {
                thread.interrupt();
                thread.join();
            }
        } catch (final Exception e) {
            // ignore
        }
        final Direction d = DIR[state.bestMove];

        // 3. if we're (far) ahead, sleep remaining time so opponent has less time to catch up
        final long elapsed = System.currentTimeMillis() - time0;
        if (DEBUG) {
            System.out.println(", elapsed: " + elapsed + "ms");
        } else if ((snake.body.size() - opponent.body.size() >= T_LEN_AHEAD || state.prolong) && elapsed < TIMEOUT) {
            try {
                Thread.sleep(TIMEOUT - elapsed);
            } catch (final Exception e) {
                // ignore
            }
        }

        // 4. return move
        return d;
    }

}
