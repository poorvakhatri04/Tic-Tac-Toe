import java.util.Scanner;
class Main{
    public static void main(String args[]){
        char board[][]=new char[3][3];
        int i,j;
        for(i=0;i<3;i++){
            for(j=0;j<3;j++)
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
            System.out.println();
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
        sc.close();
    }
    public static boolean haveWon(char[][]board, char player){
        for(int i=0;i<3;i++){
            if(board[i][0]==player && board[i][1]==player && board[i][2]==player){
                return true;
            }
        }
        for(int j=0;j<3;j++){
            if(board[0][j]==player && board[1][j]==player && board[2][j]==player){
                return true;
            }
        }
        if(board[0][0]==player && board[1][1]==player && board[2][2]==player)
            return true;
        if(board[0][2]==player && board[1][1]==player && board[2][0]==player)
            return true;
        return false;
    }
    public static void printBoard(char[][] board){
        int row,col;
        for(row=0;row<3;row++){
            for(col=0; col<3; col++)
            {
                System.out.print(board[row][col]+" | ");
            }
            System.out.println();
        }
    }
}