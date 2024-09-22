import java.util.Scanner;
class Main{
    public static void main(String args){
        char board[][]=new char[3][3];
        for(int i=0;i<board.length;i++){
            for(int j=0;j<board[i].length;j++);
            {
                board[i][j]=' ';
            }
        }
        char player='X';
        boolean gameOver=false;
        Scanner sc=new Scanner(System.in);
        while(!gameOver){
            printBoard(board);
            System.out.println("Player "+player+" enters:");
            int row=sc.nextInt();
            int col=sc.nextInt();
            if(board[row][col]==' '){
                board[row][col]=player;
                gameOver=haveWon(board,player);
                if(gameOver){
                    System.out.println("Player "+player+" has won!");
                }
                else{
                    player=(player=='X')?'O':'X';
                }
            }else{
                System.out.println("Invalid move.Try again!");
            }
        }
        printBoard(board);
    }
    public static boolean haveWon(char[][]board, char player){

    }
    public static void printBoard(char[][] board){
        for(int i=0;i<board.length;i++){
            for(int j=0;j<board[i].length;j++);
            {
                System.out.print(board[i][j]+" ");
            }
            System.out.println();
        }
    }
}