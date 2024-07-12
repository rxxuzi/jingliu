import content.RemoteFile
import content.Tree.addPathToTree
import javafx.application.Platform
import javafx.geometry.{Insets, Orientation}
import javafx.scene.Node
import javafx.scene.control.cell.TextFieldTreeCell
import javafx.scene.control.*
import javafx.scene.image.{Image, ImageView}
import javafx.scene.layout.{BorderPane, HBox}
import javafx.scene.text.Text
import javafx.util.StringConverter
import ssh.SSHManager

import java.nio.file.Files
import scala.jdk.CollectionConverters.*
import scala.util.Try

class ViewTab(sshManager: SSHManager, updateTitle: () => Unit) {
  private val statusArea = new TextArea()
  statusArea.setEditable(false)
  statusArea.setPrefRowCount(3)

  private val fileTreeView = new TreeView[RemoteFile]()
  fileTreeView.setShowRoot(false)
  fileTreeView.setCellFactory(_ => new TextFieldTreeCell[RemoteFile](new StringConverter[RemoteFile]() {
    override def toString(rf: RemoteFile): String = rf.name
    override def fromString(string: String): RemoteFile = null
  }))

  private val contentArea = new ScrollPane()
  contentArea.setFitToWidth(true)
  contentArea.setFitToHeight(true)

  private val searchField = new TextField()
  searchField.setPromptText("Search files...")

  def getContent: BorderPane = {
    val refreshButton = new Button("Refresh")
    refreshButton.setOnAction(_ => updateFileTree())

    val searchButton = new Button("Search")
    searchButton.setOnAction(_ => performSearch())

    fileTreeView.getSelectionModel.selectedItemProperty().addListener((_, _, newValue) => {
      if (newValue != null && !newValue.getValue.isDirectory) {
        viewFileContent(newValue.getValue)
      }
    })

    val splitPane = new SplitPane()
    splitPane.setOrientation(Orientation.HORIZONTAL)
    splitPane.getItems.addAll(fileTreeView, contentArea)
    splitPane.setDividerPositions(0.3)

    val topPane = new HBox(10)
    topPane.setPadding(new Insets(10))
    topPane.getChildren.addAll(refreshButton, searchField, searchButton)

    val content = new BorderPane()
    content.setTop(topPane)
    content.setCenter(splitPane)
    content.setBottom(statusArea)

    BorderPane.setMargin(statusArea, new Insets(10, 0, 0, 0))

    content
  }

  private def performSearch(): Unit = {
    val searchTerm = searchField.getText.trim.toLowerCase
    if (searchTerm.isEmpty) {
      statusArea.appendText("Please enter a search term.\n")
      return
    }

    if (sshManager.isConnected) {
      sshManager.withSSH { ssh =>
        Try {
          val homeDir = ssh.exec("echo $HOME").trim
          val searchCommand = s"find $homeDir -iname '*$searchTerm*'"
          val searchResults = ssh.exec(searchCommand).split("\n").filter(_.nonEmpty)

          Platform.runLater(() => {
            val rootItem = new TreeItem[RemoteFile](RemoteFile("Search Results", homeDir, isDirectory = true))
            searchResults.foreach { path =>
              val relativePath = path.replace(homeDir, "").stripPrefix("/")
              addPathToTree(rootItem, relativePath, path, homeDir)
            }
            fileTreeView.setRoot(rootItem)
            statusArea.appendText(s"Found ${searchResults.length} results for '$searchTerm'\n")
          })
        }.recover {
          case ex => Platform.runLater(() => {
            statusArea.appendText(s"Search failed: ${ex.getMessage}\n")
          })
        }
      }.recover {
        case ex => Platform.runLater(() => {
          statusArea.appendText(s"SSH operation failed: ${ex.getMessage}\n")
        })
      }
    } else {
      statusArea.appendText("Not connected. Please connect to SSH first.\n")
    }
  }

  private def viewFileContent(file: RemoteFile): Unit = {
    if (sshManager.isConnected) {
      sshManager.withSSH { ssh =>
        Try {
          val tempDir = Files.createTempDirectory("ssh-viewer")
          val tempFile = tempDir.resolve(file.name)
          ssh.get(file.fullPath, tempFile.toString)

          val contentNode = if (isImageFile(file.name)) {
            val image = new Image(tempFile.toUri.toString)
            val imageView = new ImageView(image)
            imageView.setPreserveRatio(true)
            imageView.setFitWidth(contentArea.getWidth - 20)
            imageView
          } else if (isTextFile(tempFile)) {
            val content = new String(Files.readAllBytes(tempFile))
            val textArea = new TextArea(content)
            textArea.setEditable(false)
            textArea.setWrapText(true)
            textArea
          } else {
            val text = new Text(s"This is a binary file: ${file.name}\nFile size: ${Files.size(tempFile)} bytes")
            text
          }

          Platform.runLater(() => {
            contentArea.setContent(contentNode)
            statusArea.appendText(s"Viewing file: ${file.name}\n")
          })

          Files.delete(tempFile)
          Files.delete(tempDir)
        }.recover {
          case ex => Platform.runLater(() => {
            statusArea.appendText(s"Failed to view file: ${ex.getMessage}\n")
          })
        }
      }.recover {
        case ex => Platform.runLater(() => {
          statusArea.appendText(s"SSH operation failed: ${ex.getMessage}\n")
        })
      }
    } else {
      statusArea.appendText("Not connected. Please connect to SSH first.\n")
    }
  }

  private def isImageFile(fileName: String): Boolean = {
    val imageExtensions = Set(".jpg", ".jpeg", ".png", ".gif", ".bmp")
    imageExtensions.exists(ext => fileName.toLowerCase.endsWith(ext))
  }

  private def isTextFile(file: java.nio.file.Path): Boolean = {
    Try {
      val bytes = Files.readAllBytes(file)
      val n = Math.min(bytes.length, 1000) // Check only first 1000 bytes
      val numOfNonPrintable = bytes.take(n).count(b => b < 32 && b != 9 && b != 10 && b != 13)
      numOfNonPrintable.toDouble / n < 0.05 // If less than 5% non-printable characters, consider it text
    }.getOrElse(false)
  }

  private def updateFileTree(): Unit = {
    if (sshManager.isConnected) {
      sshManager.withSSH { ssh =>
        Try {
          val homeDir = ssh.exec("echo $HOME").trim
          val fileList = ssh.exec(s"find $homeDir -type d -o -type f").split("\n").filter(_.nonEmpty)
          val rootItem = new TreeItem[RemoteFile](RemoteFile("Root", homeDir, isDirectory = true))

          fileList.foreach { path =>
            val relativePath = path.replace(homeDir, "").stripPrefix("/")
            addPathToTree(rootItem, relativePath, path, homeDir)
          }

          Platform.runLater(() => {
            fileTreeView.setRoot(rootItem)
            statusArea.appendText("Remote file tree updated.\n")
          })
        }.recover {
          case ex => Platform.runLater(() => {
            statusArea.appendText(s"Failed to list remote files: ${ex.getMessage}\n")
          })
        }
      }.recover {
        case ex => Platform.runLater(() => {
          statusArea.appendText(s"SSH operation failed: ${ex.getMessage}\n")
        })
      }
    } else {
      statusArea.appendText("Not connected. Please connect to SSH first.\n")
    }
  }
  
}

