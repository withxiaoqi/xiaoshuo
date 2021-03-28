package com.java2nb.novel.core.crawl;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java2nb.novel.core.utils.*;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.entity.BookIndex;
import com.java2nb.novel.utils.Constants;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.compile;

/**
 * 爬虫解析器
 *
 * @author Administrator
 */
@Slf4j
public class CrawlParser {

    private static IdWorker idWorker = new IdWorker();

    public static final Integer BOOK_INDEX_LIST_KEY = 1;

    public static final Integer BOOK_CONTENT_LIST_KEY = 2;

    private static RestTemplate restTemplate = RestTemplateUtil.getInstance("utf-8");

    private static ThreadLocal<Integer> retryCount = new ThreadLocal<>();

    @SneakyThrows
    public static Book parseBook(RuleBean ruleBean, String bookId) {
        Book book = new Book();
        String bookDetailUrl = ruleBean.getBookDetailUrl().replace("{bookId}", bookId);
        String bookDetailHtml = getByHttpClientWithChrome(bookDetailUrl);
        if (bookDetailHtml != null) {
            Pattern bookNamePatten = compile(ruleBean.getBookNamePatten());
            Matcher bookNameMatch = bookNamePatten.matcher(bookDetailHtml);
            boolean isFindBookName = bookNameMatch.find();
            if (isFindBookName) {
                String bookName = bookNameMatch.group(1);
                //设置小说名
                book.setBookName(bookName);
                Pattern authorNamePatten = compile(ruleBean.getAuthorNamePatten());
                Matcher authorNameMatch = authorNamePatten.matcher(bookDetailHtml);
                boolean isFindAuthorName = authorNameMatch.find();
                if (isFindAuthorName) {
                    String authorName = authorNameMatch.group(1);
                    //设置作者名
                    book.setAuthorName(authorName);
                    if (StringUtils.isNotBlank(ruleBean.getPicUrlPatten())) {
                        Pattern picUrlPatten = compile(ruleBean.getPicUrlPatten());
                        Matcher picUrlMatch = picUrlPatten.matcher(bookDetailHtml);
                        boolean isFindPicUrl = picUrlMatch.find();
                        System.out.println("封面地址："+isFindPicUrl);
                        if (isFindPicUrl) {
                            String picUrl = picUrlMatch.group(2);
                            if (StringUtils.isNotBlank(picUrl) && StringUtils.isNotBlank(ruleBean.getPicUrlPrefix())) {
                                picUrl = ruleBean.getPicUrlPrefix() + picUrl;
                            }
                            //设置封面图片路径
                            book.setPicUrl(picUrl);
                        }
                    }
                    if (StringUtils.isNotBlank(ruleBean.getScorePatten())) {
                        Pattern scorePatten = compile(ruleBean.getScorePatten());
                        Matcher scoreMatch = scorePatten.matcher(bookDetailHtml);
                        boolean isFindScore = scoreMatch.find();
                        if (isFindScore) {
                            String score = scoreMatch.group(1);
                            //设置评分
                            book.setScore(Float.parseFloat(score));
                        }
                    }
                    if (StringUtils.isNotBlank(ruleBean.getVisitCountPatten())) {
                        Pattern visitCountPatten = compile(ruleBean.getVisitCountPatten());
                        Matcher visitCountMatch = visitCountPatten.matcher(bookDetailHtml);
                        boolean isFindVisitCount = visitCountMatch.find();
                        if (isFindVisitCount) {
                            String visitCount = visitCountMatch.group(1);
                            //设置访问次数
                            book.setVisitCount(Long.parseLong(visitCount));
                        }
                    }

                    String desc = bookDetailHtml.substring(bookDetailHtml.indexOf(ruleBean.getDescStart()) + ruleBean.getDescStart().length());
                    desc = desc.substring(0, desc.indexOf(ruleBean.getDescEnd()));
                    //过滤掉简介中的特殊标签
                    desc = desc.replaceAll("<a[^<]+</a>", "")
                            .replaceAll("<font[^<]+</font>", "")
                            .replaceAll("<p>\\s*</p>", "")
                            .replaceAll("<p>", "")
                            .replaceAll("</p>", "<br/>");
                    //设置书籍简介
                    book.setBookDesc(desc);
                    if (StringUtils.isNotBlank(ruleBean.getStatusPatten())) {
                        Pattern bookStatusPatten = compile(ruleBean.getStatusPatten());
                        Matcher bookStatusMatch = bookStatusPatten.matcher(bookDetailHtml);
                        boolean isFindBookStatus = bookStatusMatch.find();
                        if (isFindBookStatus) {
                            String bookStatus = bookStatusMatch.group(1);
                            if (ruleBean.getBookStatusRule().get(bookStatus) != null) {
                                //设置更新状态
                                book.setBookStatus(ruleBean.getBookStatusRule().get(bookStatus));
                            }
                        }
                    }

                    if (StringUtils.isNotBlank(ruleBean.getUpadateTimePatten()) && StringUtils.isNotBlank(ruleBean.getUpadateTimeFormatPatten())) {
                        Pattern updateTimePatten = compile(ruleBean.getUpadateTimePatten());
                        Matcher updateTimeMatch = updateTimePatten.matcher(bookDetailHtml);
                        boolean isFindUpdateTime = updateTimeMatch.find();
                        if (isFindUpdateTime) {
                            String updateTime = updateTimeMatch.group(1);
                            //设置更新时间
                            book.setLastIndexUpdateTime(new SimpleDateFormat(ruleBean.getUpadateTimeFormatPatten()).parse(updateTime));

                        }
                    }

                }
                if (book.getVisitCount() == null && book.getScore() != null) {
                    //随机根据评分生成访问次数
                    book.setVisitCount(RandomBookInfoUtil.getVisitCountByScore(book.getScore()));
                } else if (book.getVisitCount() != null && book.getScore() == null) {
                    //随机根据访问次数生成评分
                    book.setScore(RandomBookInfoUtil.getScoreByVisitCount(book.getVisitCount()));
                } else if (book.getVisitCount() == null && book.getScore() == null) {
                    //都没有，设置成固定值
                    book.setVisitCount(Constants.VISIT_COUNT_DEFAULT);
                    book.setScore(6.5f);
                }
            }
        }
        return book;
    }

    public static Map<Integer, List> parseBookIndexAndContent(String sourceBookId, Book book, RuleBean ruleBean, Map<Integer, BookIndex> hasIndexs) {
        Map<Integer, List> result = new HashMap<>(2);
        result.put(BOOK_INDEX_LIST_KEY, new ArrayList(0));
        result.put(BOOK_CONTENT_LIST_KEY, new ArrayList(0));

        Date currentDate = new Date();

        List<BookIndex> indexList = new ArrayList<>();
        List<BookContent> contentList = new ArrayList<>();
        //读取目录
        String indexListUrl = ruleBean.getBookIndexUrl().replace("{bookId}", sourceBookId);
        String indexListHtml = getByHttpClientWithChrome(indexListUrl);

        if (indexListHtml != null) {
            if (StringUtils.isNotBlank(ruleBean.getBookIndexStart())) {
                indexListHtml = indexListHtml.substring(indexListHtml.indexOf(ruleBean.getBookIndexStart()) + ruleBean.getBookIndexStart().length());
            }
            Pattern indexIdPatten = compile(ruleBean.getIndexIdPatten());
            Matcher indexIdMatch = indexIdPatten.matcher(indexListHtml);

            Pattern indexNamePatten = compile(ruleBean.getIndexNamePatten());
            Matcher indexNameMatch = indexNamePatten.matcher(indexListHtml);

            boolean isFindIndex = indexIdMatch.find() && indexNameMatch.find();

            int indexNum = 0;

            //总字数
            Integer totalWordCount = book.getWordCount() == null ? 0 : book.getWordCount();
            List<String> indexNameList = new ArrayList<>();

            System.out.println(isFindIndex);
            while (isFindIndex) {

                try {
                    Thread.currentThread().sleep(1000*5);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                BookIndex hasIndex = hasIndexs.get(indexNum);
                String indexName = indexNameMatch.group(2);
//                if (indexNameList.contains(indexName)) {
//                    continue;
//                } else {
//                    indexNameList.add(indexName);
//                }

                if (hasIndex == null || !StringUtils.deleteWhitespace(hasIndex.getIndexName()).equals(StringUtils.deleteWhitespace(indexName))) {

                    String sourceIndexId = indexIdMatch.group(1);
                    String bookContentUrl = ruleBean.getBookContentUrl();
                    int calStart = bookContentUrl.indexOf("{cal_");
                    if (calStart != -1) {
                        //内容页URL需要进行计算才能得到
                        String calStr = bookContentUrl.substring(calStart, calStart + bookContentUrl.substring(calStart).indexOf("}"));
                        String[] calArr = calStr.split("_");
                        int calType = Integer.parseInt(calArr[1]);
                        if (calType == 1) {
                            ///{cal_1_1_3}_{bookId}/{indexId}.html
                            //第一种计算规则，去除第x个参数的最后y个字母
                            int x = Integer.parseInt(calArr[2]);
                            int y = Integer.parseInt(calArr[3]);
                            String calResult;
                            if (x == 1) {
                                calResult = sourceBookId.substring(0, sourceBookId.length() - y);
                            } else {
                                calResult = sourceIndexId.substring(0, sourceBookId.length() - y);
                            }

                            if (calResult.length() == 0) {
                                calResult = "0";

                            }

                            bookContentUrl = bookContentUrl.replace(calStr + "}", calResult);
                        }

                    }

                    String contentUrl = bookContentUrl.replace("{bookId}", sourceBookId).replace("{indexId}", sourceIndexId);

                    //查询章节内容
                    String contentHtml = getByHttpClientWithChrome(contentUrl);
                    if (contentHtml != null && !contentHtml.contains("正在手打中")) {
                        String content = contentHtml.substring(contentHtml.indexOf(ruleBean.getContentStart()) + ruleBean.getContentStart().length());
                        content = content.substring(0, content.indexOf(ruleBean.getContentEnd()));
                        //插入章节目录和章节内容
                        BookIndex bookIndex = new BookIndex();
                        bookIndex.setIndexName(indexName);
                        bookIndex.setIndexNum(indexNum);
                        Integer wordCount = StringUtil.getStrValidWordCount(content);
                        bookIndex.setWordCount(wordCount);
                        indexList.add(bookIndex);

                        BookContent bookContent = new BookContent();
                        bookContent.setContent(content);
                        contentList.add(bookContent);

                        if (hasIndex != null) {
                            //章节更新
                            bookIndex.setId(hasIndex.getId());
                            bookContent.setIndexId(hasIndex.getId());

                            //计算总字数
                            totalWordCount = (totalWordCount+wordCount-hasIndex.getWordCount());
                        } else {
                            //章节插入
                            //设置目录和章节内容
                            Long indexId = idWorker.nextId();
                            bookIndex.setId(indexId);
                            bookIndex.setBookId(book.getId());
                            bookIndex.setCreateTime(currentDate);
                            bookContent.setIndexId(indexId);
                            //计算总字数
                            totalWordCount += wordCount;
                        }
                        bookIndex.setUpdateTime(currentDate);
                    }
                }
                indexNum++;
                isFindIndex = indexIdMatch.find() & indexNameMatch.find();
            }

            System.out.println("章节信息"+indexList.size());
            if (indexList.size() > 0) {
                //如果有爬到最新章节，则设置小说主表的最新章节信息
                //获取爬取到的最新章节
                BookIndex lastIndex = indexList.get(indexList.size()-1);
                book.setLastIndexId(lastIndex.getId());
                book.setLastIndexName(lastIndex.getIndexName());
                book.setLastIndexUpdateTime(currentDate);

            }
            book.setWordCount(totalWordCount);
            book.setUpdateTime(currentDate);

            if (indexList.size() == contentList.size() && indexList.size() > 0) {

                result.put(BOOK_INDEX_LIST_KEY, indexList);
                result.put(BOOK_CONTENT_LIST_KEY, contentList);

            }

        }


        return result;
    }


    private static String getByHttpClient(String url) {
        try {
            ResponseEntity<String> forEntity = restTemplate.getForEntity(url, String.class);
            if (forEntity.getStatusCode() == HttpStatus.OK) {
                String body = forEntity.getBody();
                if (body.length() < Constants.INVALID_HTML_LENGTH) {
                    return processErrorHttpResult(url);
                }
                //成功获得html内容
                return body;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return processErrorHttpResult(url);

    }

    private static String getByHttpClientWithChrome(String url) {
        try {

            String body = HttpUtil.getByHttpClientWithChrome(url);
            if (body != null && body.length() < Constants.INVALID_HTML_LENGTH) {
                return processErrorHttpResult(url);
            }
            //成功获得html内容
            return body;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return processErrorHttpResult(url);

    }

    @SneakyThrows
    private static String processErrorHttpResult(String url) {
        Integer count = retryCount.get();
        if (count == null) {
            count = 0;
        }
        if (count < Constants.HTTP_FAIL_RETRY_COUNT) {
            Thread.sleep(new Random().nextInt(10 * 1000));
            retryCount.set(++count);
            return getByHttpClient(url);
        }
        return null;
    }

    public static void main(String[] args) throws IOException {

        String rule="{\"bookListUrl\":\"http://m.mcmssc.com/xclass/{catId}/{page}.html\",\"catIdRule\":{\"catId1\":\"1\",\"catId2\":\"2\",\"catId3\":\"3\",\"catId4\":\"4\",\"catId5\":\"5\",\"catId6\":\"6\",\"catId7\":\"7\"},\"bookIdPatten\":\"href=\\\"/(\\\\d+_\\\\d+)/\\\"\",\"pagePatten\":\"class=\\\"page_txt\\\"\\\\s+value=\\\"(\\\\d+)/\\\\d+\\\"\\\\s+size=\",\"totalPagePatten\":\"class=\\\"page_txt\\\"\\\\s+value=\\\"\\\\d+/(\\\\d+)\\\"\\\\s+size=\",\"bookDetailUrl\":\"http://m.mcmssc.com/{bookId}/\",\"bookNamePatten\":\"<meta property=\\\"og:novel:book_name\\\" content=\\\"([^/]+)\\\"\",\"authorNamePatten\":\"<a\\\\s+href=\\\"/author/\\\\d+/\\\">([^/]+)</a>\",\"picUrlPatten\":\"<img ([^/]+) src=\\\"([^>]+)\\\" +onerror=\",\"picUrlPrefix\":\"http://m.mcmssc.com/\",\"statusPatten\":\">状态：([^/]+)<\",\"bookStatusRule\":{\"连载\":0,\"全本\":1},\"visitCountPatten\":\">点击：(\\\\d+)<\",\"descStart\":\"<p class=\\\"review\\\">\",\"descEnd\":\"</p>\",\"bookIndexUrl\":\"http://m.mcmssc.com/\",\"indexIdPatten\":\"<a\\\\s+href=\\\"/\\\\d+_\\\\d+/(\\\\d+)\\\\.html\\\">[^/]+</a>\",\"indexNamePatten\":\"<a\\\\s+href=\\\"/\\\\d+_\\\\d+/\\\\d+\\\\.html\\\">([^/]+)</a>\",\"bookContentUrl\":\"http://www.mcmssc.com/{bookId}/{indexId}.html\",\"contentStart\":\"</p>\",\"contentEnd\":\"<div align=\\\"center\\\">\"}";
        RuleBean ruleBean = new ObjectMapper().readValue(rule, RuleBean.class);

        String matcherStr = ruleBean.getBookNamePatten();
        matcherStr="<a style=\"\" href=\"/\\d+_\\d+/(\\d+)\\.html\">([^/]+)</a>";
        StringBuffer stringBuffer = new StringBuffer();
        stringBuffer.append("<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "    <meta charset=\"utf-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1\">\n" +
                "    <title>从亡国公主到第一高手最新章节_从亡国公主到第一高手全文阅读_香已燃起_笔趣阁</title>\n" +
                "    <meta name=\"keywords\" content=\"从亡国公主到第一高手,香已燃起,从亡国公主到第一高手最新章节\" />\n" +
                "    <meta name=\"description\" content=\"从亡国公主到第一高手最新章节由网友提供，《从亡国公主到第一高手》情节跌宕起伏、扣人心弦，是一本情节与文笔俱佳的，笔趣阁免费提供从亡国公主到第一高手最新清爽干净的文字章节在线阅读。\" />\n" +
                "    \n" +
                "    <meta property=\"og:type\" content=\"novel\" />\n" +
                "    <meta property=\"og:title\" content=\"从亡国公主到第一高手\" />\n" +
                "    <meta property=\"og:description\" content=\"穿越到这个低武世界后，月如霜身为东夏长公主，一直要风得风，要雨得雨，日子滋润的不得了。<br/><br/>可惜十五岁那年，她的好日子到了头，她的孪生妹妹月如雪灌醉了她，代她嫁给了南梁镇北王萧棠。<br/><br/>她十六岁那年，月如雪因为与侍卫私会被抓，而被遣回东夏，南梁自此与东夏反目成仇。<br/><br/>她十八岁那年，东夏被北齐灭国，她成了亡国公主。月如霜醒悟了，她要权势，她要武功，只有这样，她才能保护自己所爱之人。\" />\n" +
                "    <meta property=\"og:image\" content=\"/files/article/image/123/123195/123195s.jpg\" />\n" +
                "    <meta property=\"og:novel:category\" content=\"玄幻奇幻\" />\n" +
                "    <meta property=\"og:novel:author\" content=\"香已燃起\" />\n" +
                "    <meta property=\"og:novel:book_name\" content=\"从亡国公主到第一高手\" />\n" +
                "    <meta property=\"og:novel:read_url\" content=\"/123_123195/\" />\n" +
                "    <meta property=\"og:novel:update_time\" content=\"2021-03-02 13:00:00\" />\n" +
                "    <meta property=\"og:novel:latest_chapter_name\" content=\"第24章 旧伤\" />\n" +
                "    <meta property=\"og:novel:latest_chapter_url\" content=\"/123_123195/48686574.html\" />\n" +
                "\n" +
                "    <link rel=\"stylesheet\" href=\"/layui/css/layui.css\" />\n" +
                "    <link rel=\"stylesheet\" href=\"/static/css/xiaoshuo.css\" />\n" +
                "    <script type=\"text/javascript\" src=\"/static/js/jq.min.js\"></script>\n" +
                "    <script type=\"text/javascript\" src=\"/static/js/Post.js\"></script>\n" +
                "    <script type=\"text/javascript\" src=\"/static/js/wap.js\"></script>\n" +
                "    <script type=\"text/javascript\" src=\"/static/js/bqg.js\"></script>\n" +
                "    <script type=\"text/javascript\" src=\"/static/js/index.js\"></script>\n" +
                "    \n" +
                "    <script type=\"text/javascript\">\n" +
                "    if(isMobileBrowser())\n" +
                "    {\n" +
                "        document.location.href='http://m.mcmssc.com/123_123195/';\n" +
                "    }\n" +
                "    </script>\n" +
                "\n" +
                "</head>\n" +
                "<body>\n" +
                "<div id=\"wrapper\">\n" +
                "<div class=\"ywtop\">\n" +
                "    <div class=\"ywtop_con\">\n" +
                "        <div class=\"ywtop_sethome\">\n" +
                "            <a onclick=\"this.style.behavior='url(#default#homepage)';this.setHomePage('http://www.mcmssc.com/');\" href=\"#\">将笔趣阁设为首页</a>\n" +
                "        </div>\n" +
                "        <div class=\"ywtop_addfavorite\">\n" +
                "            <a href=\"javascript:window.external.addFavorite('http://www.mcmssc.com/','长乐歌最新章节_长乐歌全文阅读_三戒大师_笔趣阁')\">收藏笔趣阁</a>\n" +
                "        </div>\n" +
                "        <div class=\"nri\">\n" +
                "                        <form name=\"mylogin\" id=\"mylogin\" method=\"post\" action=\"/index.php?s=/web/index/login\">\n" +
                "                <div class=\"cc\">\n" +
                "                    <div class=\"txt\">账号：</div>\n" +
                "                    <div class=\"inp\"><input type=\"text\" name=\"uname\" id=\"uname\"></div>\n" +
                "                </div>\n" +
                "                <div class=\"cc\">\n" +
                "                    <div class=\"txt\">密码：</div>\n" +
                "                    <div class=\"inp\"><input type=\"password\" name=\"pass\" id=\"pass\"></div>\n" +
                "                </div>\n" +
                "                <div class=\"frii\">\n" +
                "                    <input type=\"submit\" class=\"int\" value=\" \">\n" +
                "                </div>\n" +
                "                <div class=\"ccc\">\n" +
                "                    <div class=\"txtt\"></div>\n" +
                "                    <div class=\"txtt\"><a href=\"/index.php?s=/web/index/reg\">用户注册</a></div>\n" +
                "                </div>\n" +
                "            </form>\n" +
                "                    </div>\n" +
                "    </div>\n" +
                "</div>\n" +
                "\n" +
                "<div class=\"header\">\n" +
                "    <div class=\"header_logo\">\n" +
                "        <a href=\"http://www.mcmssc.com/\">笔趣阁</a>\n" +
                "    </div>\n" +
                "    <div class=\"header_search\">\n" +
                "        <form name=\"mysearch\" id=\"mysearch\" method=\"get\" action=\"/search.html\">\n" +
                "            <input class=\"search\" id=\"bdcsMain\" name=\"name\" type=\"text\" maxlength=\"30\" value=\"可搜书名和作者，可少字但别输错字。\" onfocus=\"this.style.color = '#000000';this.focus();if(this.value=='可搜书名和作者，可少字但别输错字。'){this.value='';}\" ondblclick=\"javascript:this.value=''\">\n" +
                "            <input type=\"submit\" class=\"searchBtn\" value=\"搜索\" title=\"搜索\">\n" +
                "        </form>\n" +
                "    </div>\n" +
                "</div>\n" +
                "\n" +
                "<div class=\"clear\"></div>\n" +
                "\n" +
                "<div class=\"nav\">\n" +
                "    <ul>\n" +
                "        <li><a href=\"/\">首页</a></li>\n" +
                "        <li><a rel=\"nofollow\" href=\"/bookcase.php\">我的书架</a></li>\n" +
                "        <li><a href=\"/xuanhuanxiaoshuo/\">玄幻奇幻</a></li>\n" +
                "        <li><a href=\"/xiuzhenxiaoshuo/\">修真仙侠</a></li>\n" +
                "        <li><a href=\"/dushixiaoshuo/\">都市青春</a></li>\n" +
                "        <li><a href=\"/chuanyuexiaoshuo/\">历史穿越</a></li>\n" +
                "        <li><a href=\"/wangyouxiaoshuo/\">网游竞技</a></li>\n" +
                "        <li><a href=\"/kehuanxiaoshuo/\">科幻灵异</a></li>\n" +
                "        <li><a href=\"/qitaxiaoshuo/\">其它小说</a></li>\n" +
                "        <li><a rel=\"nofollow\" href=\"/gj.html\">阅读轨迹</a></li>\n" +
                "        <li><a href=\"/wanben/1_1\">完本小说</a></li>\n" +
                "        <li><a href=\"/paihangbang/\">排行榜单</a></li>\n" +
                "        <li><a href=\"/xiaoshuodaquan/\">小说大全</a></li>\n" +
                "    </ul>\n" +
                "</div>\n" +
                "\n" +
                "<div id=\"main\">\n" +
                "\n" +
                "<div class=\"box_con\">\n" +
                "    <div class=\"con_top\">\n" +
                "        <a href=\"/\">笔趣阁</a> &gt; 从亡国公主到第一高手最新章节列表\n" +
                "    </div>\n" +
                "    \n" +
                "    <div id=\"maininfo\">\n" +
                "        <div id=\"bookdetail\">\n" +
                "            <div id=\"info\">\n" +
                "                <h1>从亡国公主到第一高手</h1>\n" +
                "                <p>作&nbsp;&nbsp;者：<a href=\"/author/123195/\">香已燃起</a></p>\n" +
                "                <p>动&nbsp;&nbsp;作：<a href=\"javascript:;\" onclick=\"addBookCase(123195);\">加入书架</a>, <a rel=\"nofollow\" href=\"#footer\">直达底部</a></p>\n" +
                "                <p>最后更新：2021-03-02 13:00:00</p>\n" +
                "                <p>最新章节：<a href=\"/123_123195/48686574.html\">第24章 旧伤</a></p>\n" +
                "            </div>\n" +
                "            <div id=\"intro\">\n" +
                "            &nbsp;&nbsp;&nbsp;&nbsp;穿越到这个低武世界后，月如霜身为东夏长公主，一直要风得风，要雨得雨，日子滋润的不得了。<br/><br/>&nbsp;&nbsp;&nbsp;&nbsp;可惜十五岁那年，她的好日子到了头，她的孪生妹妹月如雪灌醉了她，代她嫁给了南梁镇北王萧棠。<br/><br/>&nbsp;&nbsp;&nbsp;&nbsp;她十六岁那年，月如雪因为与侍卫私会被抓，而被遣回东夏，南梁自此与东夏反目成仇。<br/><br/>&nbsp;&nbsp;&nbsp;&nbsp;她十八岁那年，东夏被北齐灭国，她成了亡国公主。月如霜醒悟了，她要权势，她要武功，只有这样，她才能保护自己所爱之人。            </div>\n" +
                "        </div>\n" +
                "        <div id=\"bookscore\">\n" +
                "            <div class=\"score\">\n" +
                "                <div class=\"score_content\" id=\"score_content\">\n" +
                "                    <div class=\"score_avg\">\n" +
                "                        <span><em>9.5</em><i>9.5</i></span>\n" +
                "                    </div>\n" +
                "                    <div class=\"score_total\">共 <span>0</span> 人<br>参与评分</div>\n" +
                "                    <ul class=\"score_list\">\n" +
                "                        <li><span>超酷</span><i style=\"width:46px\"></i> <em>0人</em></li>\n" +
                "                        <li><span>好看</span><i style=\"width:8px\"></i> <em>0人</em></li>\n" +
                "                        <li><span>一般</span><i style=\"width:4px\"></i> <em>0人</em></li>\n" +
                "                        <li><span>无聊</span><i style=\"width:3px\"></i> <em>0人</em></li>\n" +
                "                        <li><span>差劲</span><i style=\"width:6px\"></i> <em>0人</em></li>\n" +
                "                    </ul>\n" +
                "                </div>\n" +
                "                <div class=\"score_post\">\n" +
                "                    <div id=\"starBox\">\n" +
                "                        <div class=\"star_title\">给喜欢的小说评分：</div>\n" +
                "                        <ul class=\"starlist\" id=\"starlist\">\n" +
                "                            <li i=\"1\"><a href=\"javascript:void(0);\" title=\"1星\" class=\"star_one\">1</a></li>\n" +
                "                            <li i=\"2\"><a href=\"javascript:void(0);\" title=\"2星\" class=\"star_two\">2</a></li>\n" +
                "                            <li i=\"3\"><a href=\"javascript:void(0);\" title=\"3星\" class=\"star_three\">3</a></li>\n" +
                "                            <li i=\"4\"><a href=\"javascript:void(0);\" title=\"4星\" class=\"star_four\">4</a></li>\n" +
                "                            <li i=\"5\"><a href=\"javascript:void(0);\" title=\"5星\" class=\"star_five\">5</a></li>\n" +
                "                        </ul>\n" +
                "                    </div>\n" +
                "                    <div class=\"star_tip\" id=\"star_tip\" style=\"display: none;\">\n" +
                "                        <s id=\"star_tip_arrow\"><i></i></s>\n" +
                "                        <div id=\"star_desc\" class=\"star_desc\"></div>\n" +
                "                    </div>\n" +
                "                </div>\n" +
                "            </div>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div id=\"sidebar\">\n" +
                "        <div id=\"fmimg\">\n" +
                "            <img alt=\"从亡国公主到第一高手\" src=\"/files/article/image/123/123195/123195s.jpg\" onerror=\"this.src='/static/images/nocov.jpg';this.onerror=null;\" width=\"120\" height=\"150\">\n" +
                "            <span class=\"b\"></span>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "    <div id=\"listtj\">\n" +
                "        &nbsp;推荐阅读：\n" +
                "                <a href=\"/1_1694/\">终极斗罗</a>、\n" +
                "                <a href=\"/0_94/\">校花的贴身高手</a>、\n" +
                "                <a href=\"/52_52767/\">斗罗大陆4终极斗罗</a>、\n" +
                "                <a href=\"/0_69/\">最强狂兵</a>、\n" +
                "                <a href=\"/0_274/\">全职法师</a>、\n" +
                "                <a href=\"/75_75386/\">妖孽奶爸在都市</a>、\n" +
                "            </div>\n" +
                "</div>\n" +
                "\n" +
                "<script type=\"text/javascript\">var bookId = 123195;</script>\n" +
                "<script type=\"text/javascript\">\n" +
                "xiaoshuo_score.pvid={id:bookId};\n" +
                "xiaoshuo_score.getS();\n" +
                "xiaoshuo_score.initStar();\n" +
                "<!-- showCount(); -->\n" +
                "</script>\n" +
                "\n" +
                "<script>\n" +
                "(function(){\n" +
                "    var bp = document.createElement('script');\n" +
                "    var curProtocol = window.location.protocol.split(':')[0];\n" +
                "    if (curProtocol === 'https') {\n" +
                "        bp.src = 'https://zz.bdstatic.com/linksubmit/push.js';\n" +
                "    }\n" +
                "    else {\n" +
                "        bp.src = 'http://push.zhanzhang.baidu.com/push.js';\n" +
                "    }\n" +
                "    var s = document.getElementsByTagName(\"script\")[0];\n" +
                "    s.parentNode.insertBefore(bp, s);\n" +
                "})();\n" +
                "</script>\n" +
                "\n" +
                "<script src=\"/layui/layui.js\"></script>\n" +
                "<script>\n" +
                "layui.use(['element', 'form', 'layer'], function(){\n" +
                "    var element = layui.element\n" +
                "    ,form = layui.form\n" +
                "    ,layer = layui.layer;\n" +
                "    \n" +
                "    \n" +
                "});\n" +
                "</script>\n" +
                "\n" +
                "<div class=\"box_con\">\n" +
                "    <div id=\"list\">\n" +
                "        <dl>\n" +
                "            <dt>《从亡国公主到第一高手》最新章节（提示：已启用缓存技术，最新章节可能会延时显示，登录书架即可实时查看。）</dt>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48686574.html\">第24章 旧伤</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661644.html\">第23章 梅轻寒</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661643.html\">第22章 睡不醒的大师兄</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661642.html\">第21章 谷安平</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661641.html\">第20章 心中之敌</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661640.html\">第19章 有口难言</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661639.html\">第18章 盟主之争</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661638.html\">第17章 另有要事</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661637.html\">第16章 幽幻宫文灵韵</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661636.html\">第15章 幽幻宫宫主</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661635.html\">第14章 武林大会</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661634.html\">第13章 偶像之争</a></dd>\n" +
                "                        \n" +
                "            <dt>《从亡国公主到第一高手》正文</dt>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661622.html\">第1章 亡国公主</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661623.html\">第2章 邪月剑</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661624.html\">第3章 心如铁石</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661625.html\">第4章 前世回忆</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661626.html\">第5章 赤鹰司</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661627.html\">第6章 庆阳城</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661628.html\">第7章 被迫假死</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661629.html\">第8章 替嫁往事</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661630.html\">第9章 金凤堂</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661631.html\">第10章 邪月剑的邪</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661632.html\">第11章 少女情思</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661633.html\">第12章 大众偶像镇北王</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661634.html\">第13章 偶像之争</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661635.html\">第14章 武林大会</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661636.html\">第15章 幽幻宫宫主</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661637.html\">第16章 幽幻宫文灵韵</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661638.html\">第17章 另有要事</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661639.html\">第18章 盟主之争</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661640.html\">第19章 有口难言</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661641.html\">第20章 心中之敌</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661642.html\">第21章 谷安平</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661643.html\">第22章 睡不醒的大师兄</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48661644.html\">第23章 梅轻寒</a></dd>\n" +
                "                        <dd> <a style=\"\" href=\"/123_123195/48686574.html\">第24章 旧伤</a></dd>\n" +
                "                        \n" +
                "        </dl>\n" +
                "    </div>\n" +
                "    \n" +
                "</div>\n" +
                "\n" +
                "</div>\n" +
                "\n" +
                "</div>\n" +
                "\n" +
                "\n" +
                "<div id=\"footer\" class=\"footer\">\n" +
                "    <div class=\"footer_link\">\n" +
                "        &nbsp;新书推荐：\n" +
                "                <a href=\"/123_123260/\">一品妖妃：病娇王爷你别跑</a>、\n" +
                "                <a href=\"/123_123221/\">儒灭万道</a>、\n" +
                "                <a href=\"/123_123163/\">魔教开局签到魔刀千刃</a>、\n" +
                "                <a href=\"/123_123267/\">斩妖除魔就变强</a>、\n" +
                "                <a href=\"/123_123235/\">洪荒：抢我悟道茶，还想传道</a>、\n" +
                "                <a href=\"/123_123176/\">这灵气复苏有毒</a>、\n" +
                "                <a href=\"/123_123179/\">白蛇：菩提劫</a>、\n" +
                "                <a href=\"/123_123172/\">全球降临异界：神级分解师</a>、\n" +
                "                <a href=\"/123_123200/\">我真的是道帝</a>、\n" +
                "                <a href=\"/123_123238/\">天医神凰</a>、\n" +
                "                <a href=\"/123_123192/\">我能无限就职</a>、\n" +
                "                <a href=\"/123_123241/\">玄幻之末代皇子签到两百年</a>、\n" +
                "                <a href=\"/123_123173/\">原始救世</a>、\n" +
                "                <a href=\"/123_123185/\">神赐恶念</a>、\n" +
                "                <a href=\"/123_123254/\">师父我是认真的</a>、\n" +
                "                <a href=\"/123_123253/\">无暇天书</a>、\n" +
                "                <a href=\"/123_123214/\">在修真界不要惹事生非</a>、\n" +
                "                <a href=\"/123_123216/\">天道残卷之离殇</a>、\n" +
                "                <a href=\"/123_123249/\">永恒星碑</a>、\n" +
                "                <a href=\"/123_123168/\">一世孤尊</a>、\n" +
                "                <a href=\"/123_123195/\">从亡国公主到第一高手</a>、\n" +
                "                <a href=\"/123_123226/\">学霸大佬重返八零</a>、\n" +
                "                <a href=\"/123_123246/\">团宠小妖精的马甲要爆了</a>、\n" +
                "                <a href=\"/123_123257/\">我的房东是龙王</a>、\n" +
                "                <a href=\"/123_123160/\">开局签到破宗门，还以为自己废了</a>、\n" +
                "                <a href=\"/123_123258/\">随身携带技能抽奖系统</a>、\n" +
                "                <a href=\"/123_123198/\">透明影后你又又又挂热搜了</a>、\n" +
                "                <a href=\"/123_123205/\">孤行九州</a>、\n" +
                "                <a href=\"/123_123265/\">祸国狂后：妖孽皇帝别惹我</a>、\n" +
                "                <a href=\"/123_123175/\">被神都抛弃的底层召唤师</a>、\n" +
                "            </div>\n" +
                "    <div class=\"footer_cont\">\n" +
                "    <p>本站所有小说为转载作品，所有章节均由网友上传，转载至本站只是为了宣传本书让更多读者欣赏。</p>\n" +
                "    <p>Copyright © 2017 笔趣阁 All Rights Reserved. </p>\n" +
                "    <script>recordedclick(123195);tj();</script>\n" +
                "    </div>\n" +
                "    <p></p>\n" +
                "    <p>欢迎收藏本站 <a href=\"/api/sitemap.xml\" style=\"color: #FF0000;\" target=\"_blank\">网站地图</a></p>\n" +
                "</div>\n" +
                "\n" +
                "\n" +
                "</body>\n" +
                "</html>" );
        // 创建 Pattern 对象
        Pattern r = Pattern.compile(matcherStr);
        Matcher m = r.matcher(stringBuffer.toString());
        System.out.println(m.find());
        System.out.println(m.group(0));
        System.out.println(m.group(1));
        System.out.println(m.group(2));
        System.out.println(m.group(3));
        System.out.println(m.toString());

    }


}
