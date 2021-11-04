package ru.itmo.wp.web.page;

import ru.itmo.wp.web.exception.RedirectException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Map;
import java.util.function.UnaryOperator;


@SuppressWarnings({"unused", "RedundantSuppression"})
public class TicTacToePage {
    private enum Phase {
        RUNNING("RUNNING"), DRAW("DRAW"), XWON("WON_X"), OWON("WON_O");
        private final String name;

        Phase(String name) {
            this.name = name;
        }
    }

    public class State {
        public final int size = 3;
        private int freeCells = size * size;
        public final int inRowCount = 3;

        Phase phase = Phase.RUNNING;
        private boolean crossesMove = true;
        private String[][] cells;

        public State() {
            cells = new String[size][];

            for (int x = 0; x < size; x++) {
                cells[x] = new String[size];
                Arrays.fill(cells[x], "");
            }
        }

        private boolean checkBoard(String check, UnaryOperator<Integer> getRow, UnaryOperator<Integer> getCol) {
            int count = 0;
            for (int i = 0; i < size; i++) {
                int row = getRow.apply(i);
                int column = getCol.apply(i);
                if (row >= size || column >= size) {
                    break;
                }
                if (check.equals(cells[row][column])) {
                    count += 1;
                    if (count == inRowCount) {
                        return true;
                    }
                } else {
                    count = 0;
                }
            }
            return false;
        }

        private Phase checkGameState() {
            String check = getTurnCode();
            boolean won = false;
            // checking rows
            for (int row = 0; row < size; row++) {
                int finalRow = row;
                won = won || checkBoard(check, (x) -> finalRow, (x) -> x);
            }
            // checking columns
            for (int column = 0; column < size; column++) {
                int finalColumn = column;
                won = won || checkBoard(check, (x) -> x, (x) -> finalColumn);
            }
            // diagnoals
            for (int offset = 0; offset < size; offset++) {
                int finalOffset = offset;
                won = won || checkBoard(check, (x) -> finalOffset + x, (x) -> x);
            }
            // antidiagonals
            for (int offset = 0; offset < size; offset++) {
                int finalOffset = offset;
                won = won || checkBoard(check, (x) -> finalOffset + size - 1 - x, (x) -> x);
            }
            return won ? (crossesMove ? Phase.XWON : Phase.OWON) : (freeCells == 0 ? Phase.DRAW : Phase.RUNNING);
        }

        private void updateGameState() {
            phase = checkGameState();
            crossesMove = !crossesMove;
        }

        private void changeCell(int row, int col, String value) {
            if (cells[row][col].isEmpty()) {
                cells[row][col] = value;
                freeCells -= 1;
            }
        }

        public String getPhase() {
            return phase.name;
        }

        public int getSize() {
            return size;
        }

        public String[][] getCells() {
            return cells;
        }

        public boolean getCrossesMove() {
            return crossesMove;
        }

        private String getTurnCode() {
            return crossesMove ? "X" : "O";
        }
    }

    private void redirect(HttpServletRequest request) {
        State state = (State) request.getSession().getAttribute("state");
        if (state != null) {
            throw new RedirectException("TicTacToe");
        }
    }

    private void onMove(HttpServletRequest request, Map<String, Object> view) {
        HttpSession session = request.getSession();
        State state = (State) session.getAttribute("state");

        if (state.phase != Phase.RUNNING) {
            redirect(request);
        }

        String cell = "";
        for (Enumeration<String> e = request.getParameterNames(); e.hasMoreElements(); ) {
            cell = e.nextElement();
            if (cell.startsWith("cell_")) {
                break;
            }
        }

        try {
            int row = Integer.parseInt(String.valueOf(cell.charAt(5)));
            int column = Integer.parseInt(String.valueOf(cell.charAt(6)));
            if (row < 0 || row > state.size || column < 0 || column > state.size) {
                throw new IndexOutOfBoundsException("Badly formatted cell input");
            }
            boolean x_moves = state.crossesMove;

            state.changeCell(row, column, state.getTurnCode());
            state.updateGameState();

            view.put("state", state);
            session.setAttribute("state", state);
            redirect(request);
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            redirect(request);
        }
        redirect(request);
    }

    private void newGame(HttpServletRequest request, Map<String, Object> view) {
        State start_state = new State();
        view.put("state", start_state);
        request.getSession().setAttribute("state", start_state);
        redirect(request);
    }

    private void action(HttpServletRequest request, Map<String, Object> view) {
        HttpSession session = request.getSession();
        State state = (State) session.getAttribute("state");
        if (state == null) {
            State start_state = new State();
            view.put("state", start_state);
            session.setAttribute("state", start_state);
        } else {
            view.put("state", state);
        }
    }
}
