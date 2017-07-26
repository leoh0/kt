# Kubetail 마개조 version a.k.a kt

## original kubetail과의 차이점

* pod을 입력하는 것이 아닌 fzf를 이용해서 파일 선택
* 기본은 선택된것과 같은 pod들을 전체 log tailing 하고 변화가 있을시 auto reload 하도록 한다.
  ```
  kt
  ```
  * see detail gif image to [HERE](http://showterm.io/df8a9f96e761012d3bb2c)
  <img src="https://media.giphy.com/media/xUn3Cnbzg5mhGcbCco/giphy.gif" alt="kt" style="width: 480px;"/>

* -m 옵션시 자신이 원하는 pod들을 선택해서 log tailing 한다. 다만, auto reload는 지원 하지 않는다.
  ```
  kt -m
  ```
  * see detail gif image to [HERE](http://showterm.io/f4ab6a8ed080700ece976)
  <img src="https://media.giphy.com/media/3o8dFpZKZg6YQ3jWRG/giphy.gif" alt="kt -m" style="width: 480px;"/>


* -l 옵션시 선택한 pod의 전체 로그를 본다. fzf를 이용해서 log를 탐색 한다.
  ```
  kt -l
  ```
  * see detail gif image to [HERE](http://showterm.io/6381c317d2e42920c0227)
  <img src="https://media.giphy.com/media/xTkcEvbmndn14qonHq/giphy.gif" alt="kt -l" style="width: 480px;"/>

## install for macosx

### 준비할 것들

* fzf
  ```
  $ brew install fzf
  ```
* kubectl
  * https://kubernetes.io/docs/tasks/tools/install-kubectl/

### install kt

```
$ brew tap leoh0/kt && brew install kt
```

# Original Kubetail

If you want to check original Kubetail, then use below link.

* https://github.com/johanhaleby/kubetail
